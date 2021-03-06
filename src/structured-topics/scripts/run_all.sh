#!/bin/bash

BASEDIR="$HOME/pipeline"
JARS_BASEPATH="${BASEDIR}/jars"
RUN_JAVA=~/jdk8/jdk1.8.0_60/bin/java
JAVA_PARAMS='-Xms4G -Xmx10G'
JAR_ST="${JARS_BASEPATH}/structured-topics-0.0.1-SNAPSHOT_with_dependencies_2015_11_13_13_46.jar"

INPUT_FOLDER=~/pipeline/in

ddts=( )
ddts_label=( )

ddts[0]="${INPUT_FOLDER}/ddt-news-n50-485k-closure.csv.gz"
ddts_label[0]="news-n50-485k"

ddts[1]="${INPUT_FOLDER}/ddt-news-n200-345k-closure.csv.gz"
ddts_label[1]="news-n200-345k"

ddts[2]="${INPUT_FOLDER}/senses-wiki-n30-1600k.csv.gz"
ddts_label[2]="wiki-n30-1600k"

ddts[3]="${INPUT_FOLDER}/senses-wiki-n200-380k.csv.gz" 
ddts_label[3]="wiki-n200-380k"

ddts[4]="${INPUT_FOLDER}/ddt-news-n50-485k-closure-filtered.csv.gz"
ddts_label[4]="news-n50-485k-filtered"

ddts[5]="${INPUT_FOLDER}/ddt-news-n200-345k-closure-filtered.csv.gz"
ddts_label[5]="news-n200-345k-filtered"

ddts[6]="${INPUT_FOLDER}/senses-wiki-n30-1600k-filtered.csv.gz"
ddts_label[6]="wiki-n30-1600k-filtered"

ddts[7]="${INPUT_FOLDER}/senses-wiki-n200-380k-filtered.csv.gz" 
ddts_label[7]="wiki-n200-380k-filtered"


word_freqs=( )
word_freqs_label=( )

word_freqs[0]="${INPUT_FOLDER}/word-freq-news.gz"
word_freqs_label[0]="wfn"

similar_senses=()

similar_senses[0]=200
similar_senses[1]=50
similar_senses[3]=10


dir_ddt_similarity="${BASEDIR}/similarities"
mkdir ${dir_ddt_similarity}
	echo 'created '${dir_ddt_similarity}

for i in "${!ddts[@]}"; do 
  	ddt=${ddts[$i]}
	ddt_label=${ddts_label[$i]}


	# compute similarities with max pruning value for current ddt

	sense_similarities="${dir_ddt_similarity}/${ddt_label}_sense_similarities_sorted.csv.gz"
	sense_similarities_tmp="${dir_ddt_similarity}/${ddt_label}_sense_similarities.csv.gz"


	echo 'calculating sense similarities for '${ddt}' to '${sense_similarities}
	${RUN_JAVA} ${JAVA_PARAMS} -cp ${JAR_ST} \
	de.tudarmstadt.lt.structuredtopics.similarity.SenseSimilarityCalculator \
	${ddt} \
	${sense_similarities_tmp} \
	${similar_senses[0]}  &>> ${dir_ddt_similarity}'/log.txt'
	
	echo 'sorting similarities'
	zcat ${sense_similarities_tmp} | sort -k1,1 -k3,3rg | gzip -9  > ${sense_similarities}
	#rm ${sense_similarities_tmp}

	echo 'output file available at '${sense_similarities}
	continue_step='step3'


	for j in "${!word_freqs[@]}"; do
		word_freq=${word_freqs[$j]}
		word_freq_label=${word_freqs_label[$j]}
		for k in "${!similar_senses[@]}"; do
			similar_senses_value=${similar_senses[$k]}
			folder_prefix="${ddt_label}_${word_freq_label}_${similar_senses_value}sim"
			echo "running configuration $folder_prefix"
			./run_pipeline.sh ${sense_similarities} ${word_freq} ${similar_senses} false "${folder_prefix}_weighted_default"
			./run_pipeline.sh ${sense_similarities} ${word_freq} ${similar_senses} false "${folder_prefix}_weighted_pruned" 3.0 
		done
	done

done
