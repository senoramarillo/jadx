package jadx.plugins.script

import jadx.api.plugins.JadxPluginContext
import jadx.plugins.script.runtime.JadxScriptData
import jadx.plugins.script.runtime.JadxScriptTemplate
import jadx.plugins.script.runtime.data.JadxScriptAllOptions
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic.Severity
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate
import kotlin.system.measureTimeMillis
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class ScriptEval {

	companion object {
		val scriptingHost = BasicJvmScriptingHost()

		val compileConf = createJvmCompilationConfigurationFromTemplate<JadxScriptTemplate>()

		private val baseEvalConf = createJvmEvaluationConfigurationFromTemplate<JadxScriptTemplate>()

		private fun buildEvalConf(scriptData: JadxScriptData): ScriptEvaluationConfiguration {
			return ScriptEvaluationConfiguration(baseEvalConf) {
				constructorArgs(scriptData)
			}
		}
	}

	fun process(init: JadxPluginContext, scriptOptions: JadxScriptAllOptions): List<JadxScriptData> {
		val jadx = init.decompiler
		val scripts = jadx.args.inputFiles.filter { f -> f.name.endsWith(".jadx.kts") }
		if (scripts.isEmpty()) {
			return emptyList()
		}
		val scriptDataList = mutableListOf<JadxScriptData>()
		for (scriptFile in scripts) {
			val scriptData = JadxScriptData(jadx, init, scriptOptions, scriptFile)
			scriptDataList.add(scriptData)
			eval(scriptData)
		}
		return scriptDataList
	}

	suspend fun compile(script: SourceCode): ResultWithDiagnostics<CompiledScript> {
		return scriptingHost.compiler(script, compileConf)
	}

	private fun eval(scriptData: JadxScriptData) {
		scriptData.log.debug { "Loading script: ${scriptData.scriptFile.absolutePath}" }
		val execTime = measureTimeMillis {
			val result = scriptingHost.eval(
				scriptData.scriptFile.toScriptSource(),
				compileConf,
				buildEvalConf(scriptData),
			)
			processEvalResult(result, scriptData)
		}
		scriptData.log.debug { "Script '${scriptData.scriptName}' executed in ${execTime.toDuration(DurationUnit.MILLISECONDS)}" }
	}

	private fun processEvalResult(res: ResultWithDiagnostics<EvaluationResult>, scriptData: JadxScriptData) {
		val log = scriptData.log
		for (r in res.reports) {
			val msg = r.render(withSeverity = false)
			when (r.severity) {
				Severity.FATAL, Severity.ERROR -> log.error(r.exception) { "Script execution error: $msg" }
				Severity.WARNING -> log.warn { "Script execution issue: $msg" }
				Severity.INFO -> log.info { "Script report: $msg" }
				Severity.DEBUG -> {} // ignore, too verbose
			}
		}
		when (res) {
			is ResultWithDiagnostics.Success -> {
				when (val retVal = res.value.returnValue) {
					is ResultValue.Error -> log.error(retVal.error) { "Script execution error:" }
					is ResultValue.Value -> log.info { "Script execution result: $retVal" }
					is ResultValue.Unit -> {}
					ResultValue.NotEvaluated -> {}
				}
			}

			is ResultWithDiagnostics.Failure -> {
				scriptData.error = true
				log.error { "Script execution failed: ${scriptData.scriptName}" }
			}
		}
	}
}
