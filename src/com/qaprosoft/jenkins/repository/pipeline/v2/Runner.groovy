package com.qaprosoft.jenkins.repository.pipeline.v2

@Grab('org.testng:testng:6.8.8')
import org.testng.xml.XmlSuite;

import static java.util.UUID.randomUUID
import com.qaprosoft.zafira.ZafiraClient

import com.qaprosoft.scm.github.GitHub;
import com.qaprosoft.jenkins.repository.pipeline.v2.Configurator
import com.qaprosoft.jenkins.repository.pipeline.v2.Executor

class Runner extends Executor {
	//ci_run_id  param for unique test run identification
	protected def uuid
	
	protected def zc
	
	//using constructor it will be possible to redefine this folder on pipeline/jobdsl level
	protected def folderName = "Automation"
	
	// with new Zafirta implementation it could be static and finalfor any project
	protected static final String ZAFIRA_REPORT_FOLDER = "./reports/qa"
	protected static final String etafReport = "eTAF_Report"
	//TODO: /api/test/runs/{id}/export should use encoded url value as well
	protected static final String etafReportEncoded = "eTAF_5fReport"
	
	//CRON related vars
	protected def listPipelines = []
	
	
	public Runner(context) {
		super(context)
		scmClient = new GitHub(context)
	}
	
	public void runJob() {
        uuid = getUUID()
        String nodeName = "master"
//        String ZAFIRA_SERVICE_URL = Configurator.get(Configurator.Parameter.ZAFIRA_SERVICE_URL)
//        String ZAFIRA_ACCESS_TOKEN = Configurator.get(Configurator.Parameter.ZAFIRA_ACCESS_TOKEN)

        //TODO: remove master node assignment
		context.node(nodeName) {
//			// init ZafiraClient to register queued run and abort it at the end of the run pipeline
//			try {
//				zc = new ZafiraClient(context, ZAFIRA_SERVICE_URL, DEVELOP)
//				def token = zc.getZafiraAuthToken(ZAFIRA_ACCESS_TOKEN)
//                zc.queueZafiraTestRun(uuid)
//			} catch (Exception ex) {
//				printStackTrace(ex)
//			}
			nodeName = chooseNode()
		}

		context.node(nodeName) {
			context.wrap([$class: 'BuildUser']) {
				try {
					context.timestamps {

						this.prepare(context.currentBuild)
						scmClient.clone()

						def timeoutValue = Configurator.get(Configurator.Parameter.JOB_MAX_RUN_TIME)
						context.timeout(time: timeoutValue.toInteger(), unit: 'MINUTES') {
							  this.build()
							  //this.test()
						}

					}
					
				} catch (Exception ex) {
					printStackTrace(ex)
					String failureReason = getFailure(context.currentBuild)
					context.echo "failureReason: ${failureReason}"
					//explicitly execute abort to resolve anomalies with in_progress tests...
//					zc.abortZafiraTestRun(uuid, failureReason)
					throw ex
				} finally {
//                    this.exportZafiraReport()
//                    this.reportingResults()
//                    //TODO: send notification via email, slack, hipchat and whatever... based on subscrpition rules
//                    this.sendTestRunResultsEmail(emailList, failureEmailList)
//                    this.clean()
                }
			}
		}

	}

    public void rerunJobs(){

        String ZAFIRA_SERVICE_URL = Configurator.get(Configurator.Parameter.ZAFIRA_SERVICE_URL)
        String ZAFIRA_ACCESS_TOKEN = Configurator.get(Configurator.Parameter.ZAFIRA_ACCESS_TOKEN)
        boolean DEVELOP = Configurator.get("develop").toBoolean()

        context.stage('Rerun Tests'){
            try {
                zc = new ZafiraClient(context, ZAFIRA_SERVICE_URL, DEVELOP)
                def token = zc.getZafiraAuthToken(ZAFIRA_ACCESS_TOKEN)
                zc.smartRerun()
            } catch (Exception ex) {
                printStackTrace(ex)
            }
        }
    }

	//TODO: moved almost everything into argument to be able to move this methoud outside of the current class later if necessary
	protected void prepare(currentBuild) {

        Configurator.set("BUILD_USER_ID", getBuildUser())
		
		String BUILD_NUMBER = Configurator.get(Configurator.Parameter.BUILD_NUMBER)
		String CARINA_CORE_VERSION = Configurator.get(Configurator.Parameter.CARINA_CORE_VERSION)
		String suite = Configurator.get("suite")
		String branch = Configurator.get("branch")
		String browser = Configurator.get("browser")

		//TODO: improve carina to detect browser_version on the fly
		String browser_version = Configurator.get("browser_version")

		context.stage('Preparation') {
			currentBuild.displayName = "#${BUILD_NUMBER}|${suite}|${branch}"
			if (!isParamEmpty("${CARINA_CORE_VERSION}")) {
				currentBuild.displayName += "|" + "${CARINA_CORE_VERSION}"
			}
			if (!isParamEmpty(Configurator.get("browser"))) {
				currentBuild.displayName += "|${browser}"
			}
			if (!isParamEmpty(Configurator.get("browser_version"))) {
				currentBuild.displayName += "|${browser_version}"
			}
			currentBuild.description = "${suite}"
		}
	}

	protected void getResources() {
		context.echo "Do nothing in default implementation"
	}

	protected void build() {
		context.stage('Build Stage') {
			if (context.isUnix()) {
				context.sh "pwd && cd ${getWorkspace()}/docker_env && pwd"
				
				//context.sh "cd docker_env"
				//context.sh "docker-compose down"
				//context.sh "docker-compose up -d --build php-fpm apache2"
			} else {
				throw new RuntimeException("Windows is not supported yet!")
			}
		}
	}
	
	protected String chooseNode() {
		Configurator.set("node", "app")
		context.echo "node: " + Configurator.get("node")
		return Configurator.get("node")
	}

	protected String getUUID() {
		def ci_run_id = Configurator.get("ci_run_id")
		context.echo "uuid from jobParams: " + ci_run_id
		if (ci_run_id.isEmpty()) {
				ci_run_id = randomUUID() as String
		}
		context.echo "final uuid: " + ci_run_id
		return ci_run_id
	}

	protected String getFailure(currentBuild) {
		//TODO: move string constants into object/enum if possible
		currentBuild.result = 'FAILURE'
		def failureReason = "undefined failure"

		String JOB_URL = Configurator.get(Configurator.Parameter.JOB_URL)
		String BUILD_NUMBER = Configurator.get(Configurator.Parameter.BUILD_NUMBER)
		String JOB_NAME = Configurator.get(Configurator.Parameter.JOB_NAME)

		String email_list = Configurator.get("email_list")

		def bodyHeader = "<p>Unable to execute tests due to the unrecognized failure: ${JOB_URL}${BUILD_NUMBER}</p>"
		def subject = "UNRECOGNIZED FAILURE: ${JOB_NAME} - Build # ${BUILD_NUMBER}!"

		if (currentBuild.rawBuild.log.contains("COMPILATION ERROR : ")) {
			failureReason = "COMPILATION ERROR"
			bodyHeader = "<p>Unable to execute tests due to the compilation failure. ${JOB_URL}${BUILD_NUMBER}</p>"
			subject = "COMPILATION FAILURE: ${JOB_NAME} - Build # ${BUILD_NUMBER}!"
		} else if (currentBuild.rawBuild.log.contains("BUILD FAILURE")) {
			failureReason = "BUILD FAILURE"
			bodyHeader = "<p>Unable to execute tests due to the build failure. ${JOB_URL}${BUILD_NUMBER}</p>"
			subject = "BUILD FAILURE: ${JOB_NAME} - Build # ${BUILD_NUMBER}!"
		} else  if (currentBuild.rawBuild.log.contains("Aborted by ")) {
			currentBuild.result = 'ABORTED'
			failureReason = "Aborted by " + getAbortCause(currentBuild)
			bodyHeader = "<p>Unable to continue tests due to the abort by " + getAbortCause(currentBuild) + " ${JOB_URL}${BUILD_NUMBER}</p>"
			subject = "ABORTED: ${JOB_NAME} - Build # ${BUILD_NUMBER}!"
		} else  if (currentBuild.rawBuild.log.contains("Cancelling nested steps due to timeout")) {
			currentBuild.result = 'ABORTED'
			failureReason = "Aborted by timeout"
			bodyHeader = "<p>Unable to continue tests due to the abort by timeout ${JOB_URL}${BUILD_NUMBER}</p>"
			subject = "TIMED OUT: ${JOB_NAME} - Build # ${BUILD_NUMBER}!"
		}


		def body = bodyHeader + """<br>Rebuild: ${JOB_URL}${BUILD_NUMBER}/rebuild/parameterized<br>
					${etafReport}: ${JOB_URL}${BUILD_NUMBER}/${etafReportEncoded}<br>
					Console: ${JOB_URL}${BUILD_NUMBER}/console"""

		//TODO: enable emailing but seems like it should be moved to the notification code
		//context.emailext attachLog: true, body: "${body}", recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: "${subject}", to: "${email_list}"
		//	context.emailext attachLog: true, body: "${body}", recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: "${subject}", to: "${email_list},${ADMIN_EMAILS}"
		return failureReason
	}

	protected String getAbortCause(currentBuild)
	{
		def causee = ''
		def actions = currentBuild.getRawBuild().getActions(jenkins.model.InterruptedBuildAction)
		for (action in actions) {
			def causes = action.getCauses()

			// on cancellation, report who cancelled the build
			for (cause in causes) {
				causee = cause.getUser().getDisplayName()
				cause = null
			}
			causes = null
			action = null
		}
		actions = null

		return causee
	}

	protected boolean isFailure(currentBuild) {
		boolean failure = false
		if (currentBuild.result) {
			failure = "FAILURE".equals(currentBuild.result.name)
		}
		return failure
	}

	protected boolean isParamEmpty(String value) {
		if (value == null || value.isEmpty() || value.equals("NULL")) {
			return true
		} else {
			return false
		}
	}

	protected String getSubProjectFolder() {
		//specify current dir as subProject folder by default
		def subProjectFolder = "."
		if (!isParamEmpty(Configurator.get("sub_project"))) {
			subProjectFolder = "./" + Configurator.get("sub_project")
		}
		return subProjectFolder
	}

	protected void reportingResults() {
		context.stage('Results') {
			publishReport('**/reports/qa/emailable-report.html', "${etafReport}")
			
			publishReport('**/artifacts/**', 'eTAF_Artifacts')
			
			publishTestNgReports('**/target/surefire-reports/index.html', 'Full TestNG HTML Report')
			publishTestNgReports('**/target/surefire-reports/emailable-report.html', 'TestNG Summary HTML Report')

		}
	}
	
	protected void exportZafiraReport() {
		//replace existing local emailable-report.html by Zafira content
		def zafiraReport = zc.exportZafiraReport(uuid)
		if (!zafiraReport.isEmpty()) {
			context.writeFile file: "${ZAFIRA_REPORT_FOLDER}/emailable-report.html", text: zafiraReport
		}
		
		//TODO: think about method renaming because in additions it also could redefin job status in Jenkins.
		// or move below code into another method
		
		// set job status based on zafira report
		if (!zafiraReport.contains("PASSED:") && !zafiraReport.contains("PASSED (known issues):") && !zafiraReport.contains("SKIP_ALL:")) {
			context.echo "Unable to Find (Passed) or (Passed Known Issues) within the eTAF Report."
			context.currentBuild.result = 'FAILURE'
		} else if (zafiraReport.contains("SKIP_ALL:")) {
			context.currentBuild.result = 'UNSTABLE'
		}
	}

	protected void sendTestRunResultsEmail(String emailList, String failureEmailList) {
		if (emailList != null && !emailList.isEmpty()) {
			zc.sendTestRunResultsEmail(uuid, emailList, "all")
		}
		if (isFailure(context.currentBuild.rawBuild) && failureEmailList != null && !failureEmailList.isEmpty()) {
			zc.sendTestRunResultsEmail(uuid, failureEmailList, "failures")
		}
	}

	protected void publishTestNgReports(String pattern, String reportName) {
		def reports = context.findFiles(glob: "${pattern}")
		for (int i = 0; i < reports.length; i++) {
			def reportDir = new File(reports[i].path).getParentFile()
			context.echo "Report File Found, Publishing ${reports[i].path}"
			def reportIndex = ""
			if (i > 0) {
				reportIndex = "_" + i
			}
			context.publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: true, reportDir: "${reportDir}", reportFiles: "${reports[i].name}", reportName: "${reportName}${reportIndex}"])
		}
	}


	protected boolean publishReport(String pattern, String reportName) {
		def files = context.findFiles(glob: "${pattern}")
		if(files.length == 1) {
			def reportFile = files[0]
			def reportDir = new File(reportFile.path).getParentFile()
			context.echo "Report File Found, Publishing ${reportFile.path}"
			context.publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: true, reportDir: "${reportDir}", reportFiles: "${reportFile.name}", reportName: "${reportName}"])
			return true;
		} else if (files.length > 1) {
			context.echo "ERROR: too many report file discovered! count: ${files.length}"
			return false;
		} else {
			context.echo "No report file discovered: ${reportName}"
			return false;
		}
	}
	
}
