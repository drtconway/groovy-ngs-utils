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
package gngs.tools

import gngs.*
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Log
import htsjdk.variant.variantcontext.Genotype
import htsjdk.variant.variantcontext.GenotypesContext
import htsjdk.variant.variantcontext.VariantContext

import static gngs.Sex.*

/**
 * A utility class to contain some functionality for computing allele numbers / counts
 * 
 * @author Simon Sadedin
 */
@CompileStatic
class AlleleNumber {
   
    static boolean isX(String chr) {
        chr.endsWith('X')
    }
    
    static boolean isY(String chr) {
        chr.endsWith('Y')
    }
    
    static int getAlleleNumber(gngs.Sex sex, String chr) {
        
        if(sex == MALE) {
            if(isX(chr) || isY(chr))
                return 1
            else
                return 2
        }
        else
        if(sex == FEMALE) {
            if(isY(chr))
                return 0
            else
                return 2
        }
        else
            assert false : "Only MALE and FEMALE sexes are supported"
    }
}

/**
 * A tool to create a "population statistics VCF" that represents the frequency of
 * variants from a given set of samples by entries in the info field.
 * <p>
 * The following population metrics are calculated:
 * <li>AN   -   the total number of possible alleles that could have been observed at the site
 * <li>AC   -   the total number of alleles that were observed at the site
 * <li>GTC  -   the total number of individuals that were sampled to generate AN and AC
 * <p>
 * The current implementation infers the sexes of the samples so they do not need to be specified.
 * However this does rely on having sufficient coverage of the autosomes and X chromosome to determine
 * sex. If this is not true, an error will be reported.
 * <p> 
 * <b>Limitations:</b>
 * The current implementation has a number of limitations:
 * 
 * <li>Only one entry is genererated for each unique site in the input VCFs. Therefore this will
 *      <i>incorrect</i> when there is more than one allele observed at a site (multi-allelic sites).
 * <li>If a site appears twice in one of the source VCFs it may be counted incorrectly (eg: multi-allelic sites that are 
 *     decomposed)
 * <li>Only VCF information is considered, not gVCF, which is to say, an allele is counted as 
 *     unobserved at a site even if there was no coverage and therefore was not ascertained at that site.
 * 
 * These limitations should be corrected to generate truly accurate counts.
 * 
 * @author Simon Sadedin
 */
@Log
class CreatePopulationStatisticsVCF extends ToolBase {
    
    /**
     * The inferred sexes as a list
     */
    private List<gngs.Sex> sexes
    
    /**
     * The inferred sexes of the samples in the VCFs
     */
    Map<String,gngs.Sex> sampleSexes = [:]

    @Override
    public void run() {
        
        if(opts.arguments().isEmpty()) {
            parser.usage()
            System.exit(0)
        }
        
        printVCFHeader()
        
        inferSexes()
        
        VariantContext lastVariant = null
        ProgressCounter progress = new ProgressCounter(withRate: true, withTime:true, extra: {
            "chr: $lastVariant.contig pos: $lastVariant.start (${lastVariant.genotypes[0].sampleName})"
        })
        VCFSiteWalker locusWalker = new VCFSiteWalker(opts.arguments())
        locusWalker.walk { List<VariantContext> variants ->
            VariantContext v0 = variants[0]
            lastVariant = v0
            int ac = computeAlleleCount(variants)
            int an = computeAlleleNumber(v0.contig)
            int gtc = variants.size() // todo: check this is right
            printVCFSite(v0, ac, an, gtc)
            progress.count()
        }
        progress.end()
    }
    
    @CompileStatic
    @Memoized
    int computeAlleleNumber(String contig) {
       (int)sexes.sum { gngs.Sex sex ->  AlleleNumber.getAlleleNumber(sex, contig) } 
    }

    /**
     * Print out a line of the output VCF based on the given computed statistics
     * 
     * @param v0
     * @param ac
     * @param an
     */
    @CompileStatic
    private void printVCFSite(VariantContext v0, int ac, int an, int gtc) {
        println([
            v0.contig,
            v0.start,
            v0.getID(),
            v0.reference.baseString,
            v0.alternateAlleles[0],
            '.',
            '.',
            "AC=$ac;AN=$an;GTC=$gtc"
        ].join('\t'))
    }
    
    /**
     * Compute the allele count at a site, based on the given list of variants at that site.
     * 
     * @return  the number of alleles observed, based on the het / hom status of the variants
     *          and the sex of the respective samples
     */
    @CompileStatic
    int computeAlleleCount(List<VariantContext> variants) {
        
        int ac = (int)variants.sum { VariantContext vc ->
                
            gngs.Sex sex = sampleSexes[vc.genotypes[0].sampleName]
            int sampleAN = AlleleNumber.getAlleleNumber(sex, vc.contig)
                
            GenotypesContext ctx = vc.genotypes
            Genotype gt =ctx.get(0)
            if(gt.het) {
                return 1
            }
            else {
                return sampleAN
            }
        } 
    }

    /**
     * Generate a header for the population VCF. This is accomplished by copying headers from the first VCF and
     * adding INFO declarations for the population statistics entries.
     */
    private void printVCFHeader() {
        VCF result = new VCF()
        result.headerLines = new VCF(opts.arguments()[0]).headerLines
        result.addInfoHeaders([
            '##INFO=<ID=AC,Number=A,Type=Integer,Description="Allele count in genotypes, for each ALT allele, in the same order as listed">',
            '##INFO=<ID=AN,Number=1,Type=Integer,Description="Total number of alleles in called genotypes">',
            '##INFO=<ID=GTC,Number=G,Type=Integer,Description="GenoType Counts. For each ALT allele in the same order as listed = 0/0,0/1,1/1,0/2,1/2,2/2,0/3,1/3,2/3,3/3">'
        ])
        result.headerLines[-1] = result.headerLines[-1].tokenize('\t')[0..7].join('\t')
        result.print()
    }

    /**
     * Infer the sexes for the input VCFs
     */
    private void inferSexes() {
        log.info "Computing sexes for ${opts.arguments().size()} samples ..."
        sampleSexes = opts.arguments().collectEntries { String vcfPath ->
            VCF vcf = new VCF(vcfPath)
            [vcf.samples[0], vcf.guessSex()]
        }
        sexes = sampleSexes*.value
        log.info "Sample sexes: $sampleSexes"
    }
    
    static void main(String [] args) {
        cli('CreatePopulationStatisticsVCF <vcf1> <vcf2> <vcf3> ...', args) {
        }
    }

}
