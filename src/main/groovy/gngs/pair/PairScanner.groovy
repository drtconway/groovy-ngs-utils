/*
 *  Groovy NGS Utils - Some simple utilites for processing Next Generation Sequencing data.
 *
 *  Copyright (C) 2018 Simon Sadedin, ssadedin<at>gmail.com
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package gngs.pair

import gngs.*


import static Utils.human

import java.util.concurrent.ConcurrentHashMap

import groovy.transform.CompileStatic
import groovy.util.logging.Log
import groovyx.gpars.actor.Actor
import groovyx.gpars.actor.DefaultActor
import htsjdk.samtools.SamReader
import htsjdk.samtools.BAMIndex
import htsjdk.samtools.BAMIndexMetaData
import htsjdk.samtools.SAMRecord
import htsjdk.samtools.SAMRecordIterator
import htsjdk.samtools.SAMSequenceRecord

/**
 * Scans a BAM file and feeds reads encountered to a pool of {@link PairLocator}
 * instances for matching to pairs. The {@link PairLocator} is chosen based on
 * read name so that any given locator is guaranteed to see both a read and it's
 * mate, if the mate exists.
 * <p>
 * This class also supports a "sharding" parameter ({@link #shardId},
 * {@link #shardSize}, which allows only every nth read to be emitted with an
 * offset. The result is that you can run multiple instances of this class in
 * parallel and guarantee that each instance will emit a distinct set of reads.
 * 
 * @author Simon Sadedin
 */
@Log
class PairScanner {
    
    static PairScanner running = null
    
    SAM bam
    
    SAMRecord lastRead
    
    ProgressCounter progress = new ProgressCounter(withRate:true, withTime:true, extra: { 
        lastRead?.referenceName + ':' + lastRead?.alignmentStart + ", loc: " +  
        [locators*.received.sum(),locators*.paired.sum(),locators*.buffer*.size().sum()].collect {human(it)}.join(',') +
        " chimeric: ${human(locators*.chimeric.sum())}" + 
        " formatted: ${human(formatter.formatted)}, written: " + human(pairWriter.written)
    })
    
    PairWriter pairWriter
    
    PairWriter pairWriter2
    
    List<PairLocator> locatorIndex = []
    
    List<PairLocator> locators = []
    
    List<PairFilter> filters = []
    
    PairFormatter formatter 
    
    Regions regions
    
    List<Actor> actors = []
    
    int chimeric
    
    int shardId = -1
    
    int shardSize = 0
    
    int numLocators 
    
    boolean throttleWarning = false
    
    String filterExpr
    
    String debugRead = null
    
    /**
     * If more than this number of reads are unwritten, assume that we are
     * limited by downstream ability to consume our reads and start backing off
     */
    int maxWriteBufferSize = 1000000
    
    Set<Integer> chromosomesWithReads = Collections.newSetFromMap(new ConcurrentHashMap(500))
    
    PairScanner(Writer writer1, Writer writer2, int numLocators, Regions regions = null, String filterExpr = null) {
        this.pairWriter = new PairWriter(writer1)
        this.pairWriter2 = new PairWriter(writer2)
        this.formatter = new PairFormatter(1000 * 1000, pairWriter, pairWriter2)
        this.regions = regions
        this.numLocators = numLocators
        this.filterExpr = filterExpr
        progress.log = log
    }
  
    
    PairScanner(Writer writer, int numLocators, Regions regions = null, String filterExpr = null) {
        this.pairWriter = new PairWriter(writer)
        this.formatter = new PairFormatter(1000 * 1000, pairWriter)
        this.regions = regions
        this.numLocators = numLocators
        this.filterExpr = filterExpr
        progress.log = log
    }
  
    void initLocators(SAM bam) {
        
        if(this.debugRead)
            formatter.debugRead = this.debugRead
        
            
        int locatorsCreated = 0
        this.locators = []
        
        Set<Integer> sequencesWithReads = getContigsWithReads(bam)
        
        log.info "The following contigs have at least one read: " + sequencesWithReads.join(', ')
        
        // Note: since we may be running in sharded mode, certain locator positions
        // may never get used. We counter this by checking at each position
        // if the locator will be used and we keep creating locators until we 
        // have the requisite number at each of the shard positions in our array
        for(int i=0; locatorsCreated < numLocators; ++i) {
            if(shardId<0 || ((i%shardSize) == shardId)) {
                ++locatorsCreated
                
                createLocator(bam, sequencesWithReads)
            }
            else {
                this.locatorIndex.add(null)
            }
        }
        
        // Fill up any trailing null positions needed
        int requiredIndexSize = shardSize*numLocators
        while(this.locatorIndex.size()<requiredIndexSize) {
            this.locatorIndex.add(null)
        }
        
        log.info "Created ${locatorsCreated} read pair locators"
        
        this.actors = locators + filters + [
            formatter,
            pairWriter,
        ]
        
        if(pairWriter2 != null)
            this.actors << pairWriter2
            
        this.actors*.start()
    }
    
    @CompileStatic
    void createLocator(SAM bam, Set<Integer> sequencesWithReads) {
        
        PairLocator pl 
        if(filterExpr != null) {
            PairFilter filter = new PairFilter(formatter, filterExpr)
            this.filters << filter
            pl = new PairLocator(filter, sequencesWithReads)
            pl.compact = false // pass full SAMRecordPair through
        }
        else {
            pl = new PairLocator(formatter, sequencesWithReads)
        }
                
        if(this.regions)
            pl.regions = new Regions((Iterable)this.regions)        
            
        if(debugRead != null)
            pl.debugRead = debugRead
            
        this.locators << pl
        this.locatorIndex << pl
    }
    
    Set<Integer> getContigsWithReads(SAM bam) {
        Set<Integer> sequencesWithReads = Collections.newSetFromMap(new ConcurrentHashMap())
        bam.withReader { SamReader r -> 
            List<Integer> seqIndices = r.fileHeader.sequenceDictionary.sequences*.sequenceIndex
            BAMIndex index = r.index
            for(Integer ind : seqIndices) {
                BAMIndexMetaData meta = index.getMetaData(ind)
                if(meta.getAlignedRecordCount() +  meta.getUnalignedRecordCount() + meta.getNoCoordinateRecordCount()>0) {
                    sequencesWithReads.add(ind)
                }
            }
        }
        
        return sequencesWithReads
    }
    
    @CompileStatic
    void scan(SAM bam) {
        log.info "Beginning scan of $bam.samFile"
        if(debugRead != null)
            log.info "Debugging read $debugRead"
            
        running = this
        this.initLocators(bam)
        try {
            this.scanBAM(bam)
        }
        finally {
            log.info "Stopping parallel threads ..."
            
            locators.eachWithIndex { a, i -> stopActor("Locator $i", a) }
            
            filters.eachWithIndex { a, i -> stopActor("Filter $i", a) }
            
            stopActor "Formatter", formatter
            stopActor "Writer", pairWriter
            if(pairWriter2)
                stopActor "Writer2", pairWriter2
                
            progress.end()
            running = null
        }
    }
    
    /**
     * Scan all the reads in the given BAM file
     */
    @CompileStatic
    private void scanBAM(SAM bam) {
        
        final SamReader reader = bam.newReader(fast:true)
        try {
            final SAMRecordIterator i = reader.iterator()
            try{
                scanBAMIterator(i)
            }
            finally {
                i.close()
            }
        }
        finally {
            reader.close()
        }        
    }

    /**
     * Process all reads yielded by the given iterator
     * <p>
     * Note: Caller must close iterator
     */
    @CompileStatic
    private void scanBAMIterator(final SAMRecordIterator i) {
        
        // These are in the hope that we get some compiler
        // optimisations - have not tested though
        final int locatorSize = locatorIndex.size()
        final int shardId = this.shardId
        final int shardSize = this.shardSize
        final int maxBufferedReads = this.maxWriteBufferSize

        while(i.hasNext()) {
            SAMRecord read = i.next()
            lastRead = read
            progress.count()
            int hash = Math.abs(read.readName.hashCode())
            int locatorOffset = hash % locatorSize
            PairLocator locator = locatorIndex[locatorOffset]
            if(locator != null) {
                locator.sendTo(read)
                if(pairWriter.pending.get() > maxBufferedReads) {
                    if(!throttleWarning) {
                        log.info "Throttling output due to slow downstream consumption of reads"
                        throttleWarning = true
                    }
                    Thread.sleep(50)
                }
            }
            else {
                if(debugRead == read.readName)
                log.info "Read $debugRead not assigned to shard (hash=$hash, lcoffset=$locatorOffset/$locatorSize)"
            }
        }
    }
    
    void stopActor(String name, Actor actor) {
        log.info "Stopping $name"
        if(actor instanceof RegulatingActor) {
            actor.sendStop()
        }
        else {
            actor << "stop"
        }
        actor.join()
    }
}
