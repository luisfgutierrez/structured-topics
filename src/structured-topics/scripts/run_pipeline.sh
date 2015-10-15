#!/bin/bash

# Values for configuration (jar locations, base directory, ...)
BASEDIR="$HOME/mt_pipeline"
JARS_BASEPATH="${BASEDIR}/jars"

echo "Basedir: ${BASEDIR}"

JAR_ST="${JARS_BASEPATH}/structured-topics-0.0.1-SNAPSHOT_with_dependencies_2015_10_15_19_02.jar"
JAR_NSI="${JARS_BASEPATH}/noun-sense-induction_2.10-0.0.1.jar"
JAR_CW="${JARS_BASEPATH}/chinese-whispers.jar"

SPARK_SUBMIT='~/spark-1.4.1/bin/spark-submit'

# validate jars
echo 'validating jars'
if [ -f ${JAR_ST} ] 
then
	echo 'JAR_ST: '${JAR_ST}
else
	echo ${JAR_ST}' is missing' 
	exit
fi

if [ -f ${JAR_NSI} ] 
then
	 echo 'JAR_NSI: '${JAR_NSI}
else
	echo ${JAR_NSI}' is missing' 
	exit
fi

if [ -f ${JAR_CW} ]
then
 	echo 'JAR_CW: '${JAR_CW}
else
	echo ${JAR_CW}' is missing' 
	exit
fi

# parameters
EXPECTED_RUN_PARAMETERS=2
EXPECTED_CONTINUE_PARAMETERS=3
# default value, if not in continue-mode
continue_step='step0'

# validate parameters
echo 'validating parameters'
if [ $# -eq $EXPECTED_RUN_PARAMETERS ]
then 	
	input_ddt=$1
	input_word_frequency=$2
	if [ -f ${input_ddt} ]
	then
	 	echo 'input_ddt: '${input_ddt}
	else 	
		echo ${input_ddt}' is missing' 
		exit
	fi

	if [ -f ${input_word_frequency} ]
	then 	
		echo 'input_word_frequency: '${input_word_frequency}
	else 	
		echo ${input_word_frequency}' is missing' 
		exit
	fi
elif [ $# -eq $EXPECTED_CONTINUE_PARAMETERS ]
then
	continue_step=$2
	DIR_PIPELINE=${3%/}
	echo 'continuing folder '$continue_folder' at step '$continue_step
else
	echo 'Missing parameters, given '$#' expected '$EXPECTED_RUN_PARAMETERS' or '$EXPECTED_CONTINUE_PARAMETERS
	echo 'usage: run_pipeline ddt-file word-frequency-file(gz)'
	echo 'or: run_pipeline continue step1|step2|step3|step4 pipeline_result_folder'  
	exit
fi

# step 0: preparation
if [ "$continue_step" == "step0" ]
then
	DIR_PIPELINE="${BASEDIR}/pipeline_$(date +%Y_%m_%d_%H_%M_%S)"
	mkdir ${DIR_PIPELINE}
	echo 'created '${DIR_PIPELINE}
	continue_step='step1'
fi



# step 1: compute similarities
DIR_STEP_1="${DIR_PIPELINE}/1_sense_similarities"
sense_similarities="${DIR_STEP_1}/sense_similarities.csv"

if [ "$continue_step" == "step1" ]
then
	mkdir ${DIR_STEP_1}
	echo 'created '${DIR_STEP_1}

	echo 'calculating sense similarities'
	java -Xms4G -Xmx6G -cp ${JAR_ST} \
	de.tudarmstadt.lt.structuredtopics.similarity.SenseSimilarityCalculator \
	${input_ddt} \
	${sense_similarities} &> ${DIR_STEP_1}'/log.txt'

	echo 'output file available at '${sense_similarities}
	continue_step='step3'
fi

# TODO: Step 2 removed, cleanup

# step 3: cluster similarities
DIR_STEP_3="${DIR_PIPELINE}/2_clustering"
clustering_result=${DIR_STEP_3}/clusters.csv

if [ "$continue_step" == "step3" ]
then
	mkdir ${DIR_STEP_3}
	echo 'created '${DIR_STEP_3}


	echo 'performing clustering'
	java -Xms4G -Xmx6G -cp ${JAR_CW} \
	de.tudarmstadt.lt.cw.global.CWGlobal \
	-in ${sense_similarities} \
	-N 100 \
	-out ${clustering_result} &> ${DIR_STEP_3}'/log.txt'

	echo 'clustering results available at '${clustering_result}
	continue_step='step4'
fi

# step 4: build index
DIR_STEP_4="${DIR_PIPELINE}/3_index"

if [ "$continue_step" == "step4" ]
then
	mkdir ${DIR_STEP_4}
	echo 'created '${DIR_STEP_4}

	echo 'building search index'
	java -Xms4G -Xmx6G -cp ${JAR_ST} \
	de.tudarmstadt.lt.structuredtopics.classify.Indexer \
	${clustering_result} \
	${DIR_STEP_4} &> ${DIR_STEP_4}'/log.txt'
	
	echo 'index available at '${DIR_STEP_4}	

	continue_step='done'
fi


if [ "$continue_step" == "done" ]
then
	touch ${DIR_PIPELINE}/_success
	echo 'done'
fi
