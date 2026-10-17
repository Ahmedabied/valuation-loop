package uk.valuationloop.dataloader;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Loader settings, bound from loader.* (paths are relative to the repo directory, where bootRun starts).
 *
 * @param run            --loader.run=true runs the load (PPD + Code-Point + EPC -> Elasticsearch)
 * @param recreate       --loader.recreate=true drops and recreates both indices first
 * @param stats          --loader.stats=true prints the comparables-range width statistic (the README's number)
 * @param ppdFiles       Land Registry Price Paid yearly files, oldest first (later files win on C/D records)
 * @param codepointZip   OS Code-Point Open zip (Data/CSV/&lt;area&gt;.csv inside)
 * @param codepointAreas postcode areas to load centroids for
 * @param epcFile        EPC certificates already filtered to the two boroughs (tools/epc_filter.py)
 * @param epc            loader.epc.enabled: false skips the EPC file entirely (no floor area, EPC rating or refined
 *                       type; property type from PPD only). The EPC register limits address use to energy-performance
 *                       purposes, so a deployment without its own address licence runs with this off.
 * @param districts      PPD district values to keep
 * @param bulkSize       documents per Elasticsearch bulk request
 * @param statsOutcodes  outcodes the range-width sample is drawn from
 * @param statsSample    how many pseudo-requests the statistic uses
 * @param statsSeed      seed for the sample shuffle, so the number is reproducible
 */
@ConfigurationProperties("loader")
public record LoaderProperties(
        @DefaultValue("false") boolean run,
        @DefaultValue("false") boolean recreate,
        @DefaultValue("false") boolean stats,
        @DefaultValue({"../data-raw/ppd/pp-2024.csv", "../data-raw/ppd/pp-2025.csv", "../data-raw/ppd/pp-2026.csv"})
        List<String> ppdFiles,
        @DefaultValue("../data-raw/codepoint/codepo_gb.zip") String codepointZip,
        @DefaultValue({"sw", "se"}) List<String> codepointAreas,
        @DefaultValue("../data-raw/epc/epc-wandsworth-lambeth.csv") String epcFile,
        @DefaultValue({"WANDSWORTH", "LAMBETH"}) List<String> districts,
        @DefaultValue("1000") int bulkSize,
        @DefaultValue({"SW11", "SW4", "SW12", "SW18", "SW2", "SW9", "SE24", "SE11"}) List<String> statsOutcodes,
        @DefaultValue("400") int statsSample,
        @DefaultValue("42") long statsSeed,
        @DefaultValue Epc epc) {

    /** loader.epc.*: whether the EPC join runs at all. */
    public record Epc(@DefaultValue("true") boolean enabled) {}
}
