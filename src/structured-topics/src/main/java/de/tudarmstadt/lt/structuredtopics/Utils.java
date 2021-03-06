package de.tudarmstadt.lt.structuredtopics;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.lt.structuredtopics.Main.InputMode;
import de.tudarmstadt.lt.structuredtopics.ddts.ClusterWord;
import de.tudarmstadt.lt.structuredtopics.ddts.Sense;
import de.tudarmstadt.lt.structuredtopics.ddts.SenseCluster;
import de.tudarmstadt.lt.structuredtopics.ddts.SingleWord;

public class Utils {

	private static final Logger LOG = LoggerFactory.getLogger(Utils.class);

	private static interface Filter {
		boolean filter(List<SingleWord> words);
	}

	protected static class PosTagFilter implements Filter {

		private Set<String> tags;

		public PosTagFilter(Set<String> tags) {
			this.tags = tags;
		}

		@Override
		public boolean filter(List<SingleWord> words) {
			for (String tag : tags) {
				for (SingleWord w : words)
					if (!tag.equalsIgnoreCase(w.getPos())) {
						return true;
					}
			}
			return false;
		}
	}

	protected static class RegexFilter implements Filter {

		private Matcher matcher;

		public RegexFilter(String regex) {
			this.matcher = Pattern.compile(regex).matcher("");
		}

		@Override
		public boolean filter(List<SingleWord> words) {
			for (SingleWord cw : words) {
				String word = cw.getText();
				int indexOfFirstHash = word.indexOf("#");
				if (indexOfFirstHash != -1) {
					String withoutPosTag = word.substring(0, indexOfFirstHash);
					matcher.reset(withoutPosTag);
				} else {
					matcher.reset(word);
				}
				if (!matcher.matches()) {
					return true;
				}
			}
			return false;
		}

	}

	public static BufferedReader openReader(File file) throws IOException {
		InputStream in = new FileInputStream(file);
		if (file.getName().endsWith(".gz")) {
			in = new GZIPInputStream(in);
		}
		Reader reader = new InputStreamReader(in, "UTF-8");
		return new BufferedReader(reader);
	}

	public static BufferedWriter openWriter(File file, boolean append) throws IOException {
		OutputStream out = new FileOutputStream(file, append);
		if (file.getName().endsWith(".gz")) {
			out = new GZIPOutputStream(out);
		}
		Writer writer = new OutputStreamWriter(out, "UTF-8");
		return new BufferedWriter(writer);
	}

	@Deprecated
	public static BufferedReader openReader(File input, InputMode mode) throws IOException {
		InputStream in = new FileInputStream(input);
		if (mode == InputMode.GZ) {
			in = new GZIPInputStream(in);
		}
		Reader reader = new InputStreamReader(in, "UTF-8");
		return new BufferedReader(reader);
	}

	@Deprecated
	public static BufferedWriter openGzipWriter(File output) throws IOException {
		OutputStream out = new FileOutputStream(output);
		out = new GZIPOutputStream(out);
		Writer writer = new OutputStreamWriter(out, "UTF-8");
		return new BufferedWriter(writer);
	}

	public static void filterClustersByPosTag(List<SenseCluster> clusters, Set<String> tags) {
		LOG.info("Filtering by POS-Tag {}", StringUtils.join(tags));
		filterClusters(clusters, new PosTagFilter(tags));
	}

	public static void filterClustersByRegEx(List<SenseCluster> clusters, String regex) {
		LOG.info("Filtering by regex {}", regex);
		filterClusters(clusters, new RegexFilter(regex));
	}

	private static void filterClusters(List<SenseCluster> clusters, Filter filter) {
		int removedClusterWords = 0;
		int removedSenses = 0;
		for (int i = clusters.size() - 1; i >= 0; i--) {
			if (i % 1000 == 0) {
				LOG.info("Filtering cluster {}/{}", clusters.size() - 1 - i, clusters.size());
			}
			SenseCluster cluster = clusters.get(i);
			Sense sense = cluster.getSense();
			boolean keepSenseWord = false;
			try {
				if (!filter.filter(sense.getWords())) {
					keepSenseWord = true;
				}
			} catch (Exception e) {
				LOG.error("Filter {} threw an exeption while filtering word {}. Word will be removed",
						filter.getClass(), sense.getFullWord(), e);
			}
			if (keepSenseWord) {
				// filter cluster words
				List<ClusterWord> clusterWords = cluster.getClusterWords();
				for (int j = clusterWords.size() - 1; j >= 0; j--) {
					ClusterWord clusterWord = clusterWords.get(j);
					if (filter.filter(clusterWord.getWords())) {
						clusterWords.remove(j);
						removedClusterWords++;
					}
				}
				// if no words left -> remove sense
				if (clusterWords.isEmpty()) {
					clusters.remove(i);
					removedSenses++;
				}
			} else {
				clusters.remove(i);
				removedSenses++;
			}
		}
		LOG.info("Filtered {} cluster words and {} entire senses", removedClusterWords, removedSenses);
	}

	public static void writeClustersToFile(Map<String, Map<Integer, List<Feature>>> clusters, File out)
			throws IOException {
		try (BufferedWriter writer = openGzipWriter(out)) {
			for (Entry<String, Map<Integer, List<Feature>>> senseClusters : clusters.entrySet()) {
				String senseWord = senseClusters.getKey();
				for (Entry<Integer, List<Feature>> senseCluster : senseClusters.getValue().entrySet()) {
					Integer senseId = senseCluster.getKey();
					writer.write(senseWord);
					writer.write("\t");
					writer.write(senseId.toString());
					writer.write("\t");
					for (Feature f : senseCluster.getValue()) {
						if (f.getSenseId() != null) {
							writer.write(f.getWord() + "#" + f.getSenseId() + ":" + f.getWeight());
						} else {
							writer.write(f.getWord());
						}
						writer.write(", ");
					}
					writer.write("\n");
				}
			}
		}
	}

	public static void writeClustersToFile(List<SenseCluster> clusters, File file) throws IOException {
		try (BufferedWriter out = openWriter(file, false)) {
			int count = 0;
			for (SenseCluster cluster : clusters) {
				count++;
				if (count % 1000 == 0) {
					LOG.info("Writing cluster {}/{}", count, clusters.size());
				}
				StringBuilder b = new StringBuilder();
				Sense sense = cluster.getSense();
				List<SingleWord> words = sense.getWords();
				appendSingleWords(b, words);
				b.append("\t").append(sense.getSenseId()).append("\t");
				for (int i = 0; i < cluster.getClusterWords().size(); i++) {
					ClusterWord clusterWord = cluster.getClusterWords().get(i);
					appendSingleWords(b, clusterWord.getWords());
					Integer relatedSenseId = clusterWord.getRelatedSenseId();
					if (relatedSenseId != null) {
						b.append("#").append(relatedSenseId);
					}
					Double weight = clusterWord.getWeight();
					if (weight != null) {
						b.append(":").append(weight);
					}
					if (i < cluster.getClusterWords().size() - 1) {
						b.append(", ");
					}
				}
				b.append("\n");
				out.write(b.toString());
			}
		}

	}

	private static void appendSingleWords(StringBuilder b, List<SingleWord> words) {
		for (int i = 0; i < words.size(); i++) {
			SingleWord word = words.get(i);
			b.append(word.getText());
			String pos = word.getPos();
			if (pos != null) {
				b.append("#").append(pos);
			}
			if (i < words.size() - 1) {
				b.append(" ");
			}

		}
	}

	public static int countLines(File file) {
		int count = 0;
		try (BufferedReader in = Utils.openReader(file)) {
			while (in.readLine() != null) {
				count++;
			}
		} catch (IOException e) {
			return -1;
		}
		return count;
	}

	public static LinkedHashSet<String> loadUniqueLines(File file) {
		LinkedHashSet<String> set = new LinkedHashSet<>();
		if (file.exists()) {
			try (BufferedReader in = Utils.openReader(file)) {
				String line = null;
				while ((line = in.readLine()) != null) {
					set.add(line);
				}
			} catch (Exception e) {
				LOG.error("Error while reading {}", file, e);
			}
			LOG.info("Loaded {} lines from {}", set.size(), file.getAbsolutePath());
		} else {
			LOG.info("{} does not exist, using empty set", file.getAbsolutePath());
		}
		return set;
	}

}
