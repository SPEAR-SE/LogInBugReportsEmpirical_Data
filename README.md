# LogInBugReportsEmpirical_Data

###Description### 
LogInBugReportsEmpirical_Data is a collection of bugs collected through ten open-source projects.

###Summary###

|Project|# studied bugs|total # bugs collected|
|---|---|---|
|ActiveMQ|594|4,649|
|AspectJ|185|863|
|Hadoop Commons|725|7,259| 
|HDFS|1,116|5,668|
|MAPREDUCE|575|4,154|
|YARN|576|3,778|
|Hive|2,231|11,862|
|PDE|2,128|2,466|
|Storm|380|1,507|
|ZooKeeper|288|1,827|
|Total|8,848|44,033|

###Project structure###

```
LogInBugReportsEmpirical_Data
│
├── bug_history:				The bug reports modification history from Eclipse projects
├── bug_reports:				List of bug reports
│   ├── ActiveMQ	
│   ├── ├── AMQ-100.json			Bug report basic content
│   │   ├── comments				Comments for each associated bug report
│   │   └── details				Details for each associated bug report
│   ├── ...
├── code_changes				Detailed Java file changes for each bug report 
├── commits					List of bug fixing commits
└── processed_data
	 ├── apache_overlapping_br.json		Apache bug reports with fixed class overlapping with logged classes
	 ├── aspectj_overlapping_br.json	AspectJ bug reports with fixed class overlapping with logged classes
	 ├── pde_overlapping_br.json		Eclipse PDE bug reports with fixed class overlapping with logged classes
	 ├── fixed_in_st_step.json		Steps count for fixed classes in stack traces from the first stack frame
	 └── code_changes.json			List of Java file changes for each bug report
```
