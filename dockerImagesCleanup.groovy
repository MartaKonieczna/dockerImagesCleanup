import org.apache.commons.lang3.StringUtils
import org.artifactory.api.repo.exception.ItemNotFoundRuntimeException
import org.artifactory.exception.CancelException

import groovy.json.JsonSlurper
import groovy.time.TimeCategory
import groovy.time.TimeDuration
import groovy.transform.Field

import java.text.SimpleDateFormat

@Field final String CONFIG_FILE_PATH = "plugins/${this.class.name}.json"
@Field final String PROPERTIES_FILE_PATH = "plugins/${this.class.name}.properties"
@Field final String DEFAULT_TIME_UNIT = "month"
@Field final int DEFAULT_TIME_INTERVAL = 1

class Global {
    static Boolean stopCleaning = false
    static Boolean pauseCleaning = false
    static int paceTimeMS = 0
}

// curl command example for running this plugin (Prior to Artifactory 5.x, use pipe '|' and not semi-colons ';' for parameters separation).
// curl -i -uadmin:password -X POST "http://localhost:8081/artifactory/api/plugins/execute/cleanup?params=timeUnit=day;timeInterval=1;repos=libs-release-local;dryRun=true;paceTimeMS=2000;disablePropertiesSupport=true"
//
// For a HA cluster, the following commands have to be directed at the instance running the script. Therefore it is best to invoke
// the script directly on an instance so the below commands can operate on same instance
// curl -i -uadmin:password -X POST "http://localhost:8081/artifactory/api/plugins/execute/cleanupCtl?params=command=pause"
// curl -i -uadmin:password -X POST "http://localhost:8081/artifactory/api/plugins/execute/cleanupCtl?params=command=resume"
// curl -i -uadmin:password -X POST "http://localhost:8081/artifactory/api/plugins/execute/cleanupCtl?params=command=stop"
// curl -i -uadmin:password -X POST "http://localhost:8081/artifactory/api/plugins/execute/cleanupCtl?params=command=adjustPaceTimeMS;value=-1000"

def pluginGroup = 'cleaners'

executions {
    cleanup(groups: [pluginGroup]) { params ->
        def timeUnit = params['timeUnit'] ? params['timeUnit'][0] as String : DEFAULT_TIME_UNIT
        def timeInterval = params['timeInterval'] ? params['timeInterval'][0] as int : DEFAULT_TIME_INTERVAL
        def repos = params['repos'] as String[]
        def dryRun = params['dryRun'] ? new Boolean(params['dryRun'][0]) : false
        def disablePropertiesSupport = params['disablePropertiesSupport'] ? new Boolean(params['disablePropertiesSupport'][0]) : false
        def paceTimeMS = params['paceTimeMS'] ? params['paceTimeMS'][0] as int : 0


    cleanupCtl(groups: [pluginGroup]) { params ->
        def command = params['command'] ? params['command'][0] as String : ''

        switch ( command ) {
            case "stop":
                Global.stopCleaning = true
                log.info "Stop request detected"
                break
            case "adjustPaceTimeMS":
                def adjustPaceTimeMS = params['value'] ? params['value'][0] as int : 0
                def newPaceTimeMS = ((Global.paceTimeMS + adjustPaceTimeMS) > 0) ? (Global.paceTimeMS + adjustPaceTimeMS) : 0
                log.info "Pacing adjustment request detected, adjusting old pace time ($Global.paceTimeMS) by $adjustPaceTimeMS to new value of $newPaceTimeMS"
                Global.paceTimeMS = newPaceTimeMS
                break
            case "pause":
                Global.pauseCleaning = true
                log.info "Pause request detected"
                break
            case "resume":
                Global.pauseCleaning = false
                log.info "Resume request detected"
                break
            default:
                log.info "Missing or invalid command, '$command'"
        }
    }
}
}

def configFile = new File(ctx.artifactoryHome.etcDir, CONFIG_FILE_PATH)


if ( configFile.exists() ) {
  
    def config = new JsonSlurper().parse(configFile.toURL())
    log.info "Schedule job policy list: $config.policies"

    def count=1
    config.policies.each{ policySettings ->
        def cron = policySettings.containsKey("cron") ? policySettings.cron as String : ["0 0 5 ? * 1"]
        def repos = policySettings.containsKey("repos") ? policySettings.repos as String[] : ["__none__"]
        def timeUnit = policySettings.containsKey("timeUnit") ? policySettings.timeUnit as String : DEFAULT_TIME_UNIT
        def timeInterval = policySettings.containsKey("timeInterval") ? policySettings.timeInterval as int : DEFAULT_TIME_INTERVAL
        def paceTimeMS = policySettings.containsKey("paceTimeMS") ? policySettings.paceTimeMS as int : 0
        def dryRun = policySettings.containsKey("dryRun") ? new Boolean(policySettings.dryRun) : false
        def disablePropertiesSupport = policySettings.containsKey("disablePropertiesSupport") ? new Boolean(policySettings.disablePropertiesSupport) : false

        jobs {
            "scheduledCleanup_$count"(cron: cron) {
                log.info "Policy settings for scheduled run at($cron): repo list($repos), timeUnit($timeUnit), timeInterval($timeInterval), paceTimeMS($paceTimeMS) dryrun($dryRun) disablePropertiesSupport($disablePropertiesSupport)"
                imageCleanup( timeUnit, timeInterval, repos, log, paceTimeMS, dryRun, disablePropertiesSupport )
            }
        }
        count++
    }  
}

private def imageCleanup(String timeUnit, int timeInterval, String[] repos, log, paceTimeMS, dryRun = false, disablePropertiesSupport = false) {
    log.info "Starting docker images cleanup for repositories $repos, until $timeInterval ${timeUnit}s ago with pacing interval $paceTimeMS ms, dryrun: $dryRun, disablePropertiesSupport: $disablePropertiesSupport"

    // Create Map(repo, paths) of skiped paths (or others properties supported in future ...)
    def skip = [:]
    if ( ! disablePropertiesSupport && repos){
        skip = getSkippedPaths(repos)
    }

    def calendarUntil = Calendar.getInstance()

    calendarUntil.add(mapTimeUnitToCalendar(timeUnit), -timeInterval)

    def calendarUntilFormatted = new SimpleDateFormat("yyyy/MM/dd HH:mm").format(calendarUntil.getTime());
    log.info "Removing all images not downloaded since $calendarUntilFormatted"

    Global.stopCleaning = false
    int cntFoundImages = 0
    int cntNoDeletePermissions = 0
    long bytesFound = 0
    long bytesFoundWithNoDeletePermission = 0
    def imagesCleanedUp = searches.imagesNotDownloadedSince(calendarUntil, calendarUntil, repos)
	log.info "Starting search of images"

}

private def getSkippedPaths(String[] repos) {
    def timeStart = new Date()
    def skip = [:]
    for (String repoKey : repos){
        def pathsTmp = []
        def aql = "items.find({\"repo\":\"" + repoKey + "\",\"type\": \"any\",\"@cleanup.skip\":\"true\"}).include(\"repo\", \"path\", \"name\", \"type\")"
        searches.aql(aql.toString()) {
            for (item in it) {
                def path = item.path + '/' + item.name
                // Root path case behavior
                if ('.' == item.path){
                    path = item.name
                }
                if ('folder' == item.type){
                    path += '/'
                }
                log.info "skip found for " + repoKey + ":" + path
                pathsTmp.add(path)
            }
        }

        // Simplify list to have only parent paths
        def paths = []
        for (path in pathsTmp.sort{ it }) {
            if (paths.size == 0 || ! path.startsWith(paths[-1])) {
			log.info "skip added for " + repoKey + ":" + path
                paths.add(path)
            }
        }

        if (paths.size > 0){
            skip[repoKey] = paths.toArray(new String[paths.size])
        }
    }
    def timeStop = new Date()
    TimeDuration duration = TimeCategory.minus(timeStop, timeStart)
    log.info "Elapsed time to retrieve paths to skip: " + duration
    return skip
}

private def mapTimeUnitToCalendar (String timeUnit) {
    switch ( timeUnit ) {
        case "minute":
            return Calendar.MINUTE
        case "hour":
            return Calendar.HOUR
        case "day":
            return Calendar.DAY_OF_YEAR
        case "month":
            return Calendar.MONTH
        case "year":
            return Calendar.YEAR
        default:
            def errorMessage = "$timeUnit is no valid time unit. Please check your request or scheduled policy."
            log.error errorMessage
            throw new CancelException(errorMessage, 400)
    }
}