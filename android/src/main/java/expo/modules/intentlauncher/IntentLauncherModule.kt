package expo.modules.intentlauncher

import expo.modules.core.Promise
import expo.modules.core.ExportedModule
import expo.modules.core.ModuleRegistry
import expo.modules.core.interfaces.ExpoMethod
import expo.modules.core.ModuleRegistryDelegate
import expo.modules.core.interfaces.ActivityProvider
import expo.modules.core.interfaces.services.UIManager
import expo.modules.core.interfaces.ActivityEventListener
import expo.modules.core.errors.CurrentActivityNotFoundException
import expo.modules.intentlauncher.exceptions.ActivityAlreadyStartedException

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Context

import android.util.Log

private const val NAME = "ExpoIntentLauncher"

private const val REQUEST_CODE = 12
private const val ATTR_ACTION = "action"
private const val ATTR_TYPE = "type"
private const val ATTR_CATEGORY = "category"
private const val ATTR_EXTRA = "extra"
private const val ATTR_DATA = "data"
private const val ATTR_FLAGS = "flags"
private const val ATTR_PACKAGE_NAME = "packageName"
private const val ATTR_CLASS_NAME = "className"
private const val ATTR_EXTRA_INTENT = "android.intent.extra.INTENT"

class IntentLauncherModule(
	context: Context,
	private val moduleRegistryDelegate: ModuleRegistryDelegate = ModuleRegistryDelegate()
) : ExportedModule(context), ActivityEventListener {
	private var pendingPromise: Promise? = null
	private val uiManager: UIManager by moduleRegistry()
	private val activityProvider: ActivityProvider by moduleRegistry()

	private inline fun <reified T> moduleRegistry() = moduleRegistryDelegate.getFromModuleRegistry<T>()

	override fun getName() = NAME

	override fun onCreate(moduleRegistry: ModuleRegistry) {
		moduleRegistryDelegate.onCreate(moduleRegistry)
	}

	fun mapToBundle(map: Map<String, Any?>): Bundle {
		val bundle: Bundle = Bundle()
		for (key: String in map.keys) {
			var value = map.get(key)
			if (value == null) {
				bundle.putString(key, null)
			} else if (value is String) {
				bundle.putString(key, value)
			} else if (value is Int) {
				bundle.putInt(key, value)
			} else if (value is Double) {
				bundle.putDouble(key, value)
			} else if (value is Long) {
				bundle.putLong(key, value)
			} else if (value is Boolean) {
				bundle.putBoolean(key, value)
			} else if (value is ArrayList<*>) {
				value = value as ArrayList<String>
				bundle.putStringArray(key, value.toTypedArray())
			} else if (value is Map<*, *>) {
				value = value as Map<String, Any?>
				bundle.putBundle(key, mapToBundle(value))
			} else if (value is Bundle) {
				bundle.putBundle(key, value)
			} else {
				throw UnsupportedOperationException("Could not put a value of " + value::class + " to bundle.")
			}
		}
		return bundle
	}

	fun createIntent(params: Map<String, Any?>): Intent {
		val intent = Intent()

		if (params.containsKey(ATTR_CLASS_NAME)) {
			intent.component = if (params.containsKey(ATTR_PACKAGE_NAME)) {
				ComponentName(
					params.get(ATTR_PACKAGE_NAME) as String,
					params.get(ATTR_CLASS_NAME) as String
				)
			} else {
				ComponentName(context, params.get(ATTR_CLASS_NAME) as String)
			}
		}

		if (params.containsKey(ATTR_ACTION)) {
			intent.setAction(params.get(ATTR_ACTION) as String)
		}

		// `setData` and `setType` are exclusive, so we need to use `setDateAndType` in that case.
		if (params.containsKey(ATTR_DATA) && params.containsKey(ATTR_TYPE)) {
			intent.setDataAndType(Uri.parse(params.get(ATTR_DATA) as String), params.get(ATTR_TYPE) as String)
		} else {
			if (params.containsKey(ATTR_DATA)) {
				intent.data = Uri.parse(params.get(ATTR_DATA) as String)
			} else if (params.containsKey(ATTR_TYPE)) {
				intent.type = params.get(ATTR_TYPE) as String
			}
		}

		if (params.containsKey(ATTR_EXTRA)) {
			val extra = (params.get(ATTR_EXTRA) as Map<String, Any?>).toMutableMap()
			
			if (extra.containsKey(ATTR_EXTRA_INTENT)) {
				val extraIntent = createIntent(extra.get(ATTR_EXTRA_INTENT) as Map<String, Any?>)
				extra.set(ATTR_EXTRA_INTENT, extraIntent)
			}
			
			intent.putExtras(mapToBundle(extra))
		}

		if (params.containsKey(ATTR_FLAGS)) {
			intent.addFlags(params.get(ATTR_EXTRA) as Int)
		}

		if (params.containsKey(ATTR_CATEGORY)) {
			intent.addCategory(params.get(ATTR_CATEGORY) as String)
		}

		return intent
	}

	@ExpoMethod
	fun startActivity(params: Map<String, Any?>, promise: Promise) {
		if (pendingPromise != null) {
			promise.reject(ActivityAlreadyStartedException())
			return
		}

		val activity = activityProvider.currentActivity
		if (activity == null) {
			promise.reject(CurrentActivityNotFoundException())
			return
		}
		
		val intent = createIntent(params)

		uiManager.registerActivityEventListener(this)
		pendingPromise = promise

		try {
			activity.startActivityForResult(intent, REQUEST_CODE)
		} catch (e: ActivityNotFoundException) {
			promise.reject(e)
			pendingPromise = null
		}
	}

	//region ActivityEventListener

	override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, intent: Intent?) {
		if (requestCode != REQUEST_CODE) return

		val response = Bundle().apply {
			putInt("resultCode", resultCode)
			if (intent != null) {
				intent.data?.let { putString(ATTR_DATA, it.toString()) }
				intent.extras?.let { putBundle(ATTR_EXTRA, it) }
			}
		}

		pendingPromise?.resolve(response)
		pendingPromise = null

		uiManager.unregisterActivityEventListener(this)
	}

	override fun onNewIntent(intent: Intent) = Unit

	//endregion
}
