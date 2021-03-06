package de.tudarmstadt.lt.structuredtopics.evaluate;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import de.tudarmstadt.lt.structuredtopics.Utils;

public class MapClustersToBabelnetSenses {

	private static final Logger LOG = LoggerFactory.getLogger(MapClustersToBabelnetSenses.class);

	private static final String OPTION_CLUSTERS_FILE = "clusters";
	private static final String OPTION_SENSES_FILE = "bnetSenses";
	private static final String OPTION_OUTPUT_FILE = "out";
	private static final String OPTION_OUTPUT_INDEX_FILE = "outDomainIndex";
	private static final String OPTION_SPLIT_MULTIWORDS = "splitMultiwords";

	public static void main(String[] args) {
		Options options = createOptions();
		try {
			CommandLine cl = new DefaultParser().parse(options, args, true);
			File clusters = new File(cl.getOptionValue(OPTION_CLUSTERS_FILE));
			LOG.info("Loading babelnet senses");
			File sensesFile = new File(cl.getOptionValue(OPTION_SENSES_FILE));
			Set<String> sensesLines = Utils.loadUniqueLines(sensesFile);
			boolean splitMultiwords = cl.hasOption(OPTION_SPLIT_MULTIWORDS);
			LOG.info("building index");
			Map<String, Map<String, Double>> index = buildIndexFromSenses(sensesLines, splitMultiwords);
			if (cl.hasOption(OPTION_OUTPUT_INDEX_FILE)) {
				File outIndex = new File(cl.getOptionValue(OPTION_OUTPUT_INDEX_FILE));
				if (!outIndex.exists()) {
					LOG.info("Writing babelnet index to {}", outIndex.getAbsolutePath());
					writeDomainIndex(index, outIndex);
				} else {
					LOG.info("Domain index {} already exists", outIndex.getAbsolutePath());
				}
			}
			LOG.info("Scoring clusters");
			File out = new File(cl.getOptionValue(OPTION_OUTPUT_FILE));
			scoreAndWriteClusters(clusters, index, out);
			LOG.info("Done, results available at {}", out.getAbsolutePath());
		} catch (ParseException e) {
			LOG.error("Invalid arguments: {}", e.getMessage());
			StringWriter sw = new StringWriter();
			try (PrintWriter w = new PrintWriter(sw)) {
				new HelpFormatter().printHelp(w, Integer.MAX_VALUE, "application", "", options, 0, 0, "", true);
			}
			LOG.error(sw.toString());
		} catch (Exception e) {
			LOG.error("Error", e);
		}
	}

	private static void writeDomainIndex(Map<String, Map<String, Double>> index, File outIndex) {
		try (BufferedWriter out = Utils.openWriter(outIndex, false)) {
			out.write("domain-name\tdomain-size\tdomain-words:weights\n");
			for (Entry<String, Map<String, Double>> domain : index.entrySet()) {
				String domainName = domain.getKey();
				Map<String, Double> domainWords = domain.getValue();
				List<String> words = domainWords.entrySet().stream()
						.sorted(/* highest value first */(a, b) -> Double.compare(b.getValue(), a.getValue()))
						.map(x -> x.getKey() + ":" + x.getValue()).collect(Collectors.toList());
				out.write(domainName + "\t" + words.size() + "\t" + StringUtils.join(words, ", ") + "\n");
			}
		} catch (Exception e) {
			LOG.error("Error", e);
		}
	}

	// domain - (sense - weight)
	private static Map<String, Map<String, Double>> buildIndexFromSenses(Set<String> sensesLines,
			boolean splitMultiwords) {
		Map<String, Map<String, Double>> domains = Maps.newHashMap();
		for (String line : sensesLines) {
			String[] split = line.split("\t");
			if (split.length > 3) {
				String domain = split[2];
				Map<String, Double> domainSenses = domains.get(domain);
				if (domainSenses == null) {
					LOG.info("Adding index for domain {}", domain);
					domainSenses = Maps.newHashMap();
					domains.put(domain, domainSenses);
				}
				String sense = split[0];
				// there are negative weights on babelnet, so take the absolute
				// value instead
				Double weight = Math.abs(Double.valueOf(split[1]));
				// words are separated by "_", so tokenize the sense to words
				// and add them too if splitting is enabled
				if (splitMultiwords) {
					String[] senseWords = sense.split("_");
					for (String s : senseWords) {
						addWeightToIndex(s.toLowerCase(), weight, domainSenses);
					}
				}
				// also put the entire word separated by whitespace for
				// multiword mappings
				addWeightToIndex(sense.toLowerCase().replace('_', ' '), weight, domainSenses);
			}
		}
		return domains;
	}

	public static void addWeightToIndex(String sense, Double weight, Map<String, Double> index) {
		Double indexWeight = index.get(sense);
		if (indexWeight != null) {
			indexWeight += weight;
		} else {
			indexWeight = weight;
		}
		index.put(sense, indexWeight);
	}

	private static void scoreAndWriteClusters(File clusters, Map<String, Map<String, Double>> index, File outFile) {
		DecimalFormat df = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
		df.setMaximumFractionDigits(340);
		try (BufferedWriter out = Utils.openWriter(outFile, false)) {
			List<String> lines = readLines(clusters);
			int size = lines.size();
			AtomicInteger count = new AtomicInteger();
			CountDownLatch latch = new CountDownLatch(lines.size());
			lines.parallelStream().forEach(line -> {
				try {
					if (count.incrementAndGet() % 100 == 0) {
						LOG.info("Progress line {}/{}", count.get(), size);
					}
					String[] split = line.split("\t");
					int clusterIndex = Integer.parseInt(split[0]);
					String[] clusterWords = split[2].split(",\\s*");
					removeTagAndIndex(clusterWords);
					double topSimpleScore = 0;
					String topSimpleScoreDomain = "";
					double topCosineScore = 0;
					String topCosineScoreDomain = "";
					double topPurityScore = 0;
					String topPurityScoreDomain = "";
					double topOverlap = 0;
					String topOverlapDomain = "";

					for (Entry<String, Map<String, Double>> domain : index.entrySet()) {
						String domainName = domain.getKey();
						double overlap = overlap(domain.getValue(), clusterWords);
						if (overlap > topOverlap) {
							topOverlap = overlap;
							topOverlapDomain = domainName;
						}
						double simpleScore = scoreSimple(domain.getValue(), clusterWords);
						if (simpleScore > topSimpleScore) {
							topSimpleScore = simpleScore;
							topSimpleScoreDomain = domainName;
						}
						double cosineScore = scoreCosine(domain.getValue(), clusterWords);
						if (cosineScore > topCosineScore) {
							topCosineScore = cosineScore;
							topCosineScoreDomain = domainName;
						}
						double purityScore = scoreSimpleWeighted(domain.getValue(), clusterWords);
						if (purityScore > topPurityScore) {
							topPurityScore = purityScore;
							topPurityScoreDomain = domainName;
						}
					}
					String topDomains = "";
					topDomains += topOverlapDomain + "\t" + df.format(topOverlap) + "\t";
					topDomains += topPurityScoreDomain + "\t" + df.format(topPurityScore) + "\t";
					topDomains += topSimpleScoreDomain + "\t" + df.format(topSimpleScore) + "\t";
					topDomains += topCosineScoreDomain + "\t" + df.format(topCosineScore);

					String outLine = clusterIndex + "\t" + clusterWords.length + "\t" + topDomains + "\t" + split[2];
					synchronized (out) {
						out.write(outLine + "\n");
					}
				} catch (Exception e) {
					LOG.error("Error", e);
				} finally {
					latch.countDown();
				}
			});
			// keep the stream open until all parallel work is done
			latch.await();
		} catch (Exception e) {
			LOG.error("Error", e);
		}

	}

	private static List<String> readLines(File clusters) throws IOException {
		List<String> lines = Lists.newArrayList();
		try (BufferedReader in = Utils.openReader(clusters)) {
			String line = null;
			while ((line = in.readLine()) != null) {
				lines.add(line);
			}
		}
		return lines;
	}

	private static void removeTagAndIndex(String[] clusterWords) {
		for (int i = 0; i < clusterWords.length; i++) {
			String word = clusterWords[i];
			int firstHash = word.indexOf("#");
			if (firstHash != -1) {
				clusterWords[i] = word.substring(0, firstHash);
			}
		}
	}

	/*
	 * weighted simple score: score multiplied by the ln of the cluster size.
	 */
	private static double scoreSimpleWeighted(Map<String, Double> index, String[] clusterWords) {
		return scoreSimple(index, clusterWords) * Math.log(clusterWords.length);
	}

	private static double overlap(Map<String, Double> index, String[] clusterWords) {
		int hits = 0;
		for (String word : clusterWords) {
			if (index.containsKey(word.toLowerCase())) {
				hits++;
			}
		}
		return (double) hits / clusterWords.length;
	}

	/*
	 * Simple score: (sum of all domain-weights of matching senses)/(size of
	 * cluster)
	 */
	private static double scoreSimple(Map<String, Double> index, String[] clusterWords) {
		int hits = 0;
		double score = 0;
		for (String word : clusterWords) {
			Double indexWeight = index.get(word.toLowerCase());
			if (indexWeight != null) {
				score += indexWeight;
				hits++;
			}
		}
		if (hits == 0) {
			return 0;
		} else {
			return score / clusterWords.length;
		}
	}

	/*
	 * Cosine distance, uses weights of the index and weights of 1 for the
	 * cluster words
	 */
	private static double scoreCosine(Map<String, Double> index, String[] clusterWords) {
		double clusterSize = clusterWords.length;
		double indexSize = 0;
		for (Double weight : index.values()) {
			indexSize += Math.abs(weight);
		}
		double score = 0;
		for (String word : clusterWords) {
			Double indexWeight = index.get(word.toLowerCase());
			if (indexWeight != null) {
				score += Math.abs(indexWeight);
			}
		}
		double result = score / (clusterSize * indexSize);
		return result;
	}

	private static Options createOptions() {
		Options options = new Options();
		Option clusters = Option.builder(OPTION_CLUSTERS_FILE).argName("clusters").desc("Clusters of senses").required()
				.hasArg().build();
		options.addOption(clusters);
		Option senses = Option.builder(OPTION_SENSES_FILE).argName("senses")
				.desc("Senses from babelnet crawlers (.csv, one per line). Three tab separated columns expected: sense, weight, domain")
				.required().hasArg().build();
		options.addOption(senses);
		Option output = Option.builder(OPTION_OUTPUT_FILE).argName("out")
				.desc("the clusters, ordered by their weight to the domain, capped at zero").required().hasArg()
				.build();
		options.addOption(output);
		Option outputIndexFile = Option.builder(OPTION_OUTPUT_INDEX_FILE).argName("outDomainIndex")
				.desc("Output file for the babelnet domain index. The file will only be created if it does not exist")
				.hasArg().build();
		options.addOption(outputIndexFile);
		Option splitMultiwords = Option.builder(OPTION_SPLIT_MULTIWORDS).argName("splitMultiwords")
				.desc("If this option is set, multiwords like 'a_b_c' will be split and added to the index as [<a b c>, <a>, <b>, <c>]")
				.build();
		options.addOption(splitMultiwords);
		return options;
	}
}
