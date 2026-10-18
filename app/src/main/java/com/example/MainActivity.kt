package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.example.network.GeminiService
import com.example.ui.theme.CbtDarkBg
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed interface JsDialogState {
  data class Alert(val message: String, val result: JsResult) : JsDialogState

  data class Confirm(val message: String, val result: JsResult) : JsDialogState

  data class Prompt(
    val message: String,
    val defaultValue: String,
    val result: JsPromptResult,
  ) : JsDialogState
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(AndroidColor.parseColor("#1A1930")),
      navigationBarStyle = SystemBarStyle.dark(AndroidColor.parseColor("#1A1930")),
    )
    setContent {
      MyApplicationTheme(darkTheme = true) {
        CbtAppScreen(onExitApp = { moveTaskToBack(true) })
      }
    }
  }
}

private const val APP_URL = "https://appassets.androidplatform.net/assets/index.html"

private val BRIDGE_INIT_JS =
  """
  (function() {
    if (window.__androidBridgeInstalled) return;
    window.__androidBridgeInstalled = true;

    // 1. Bridge navigator.clipboard.writeText for reliable Android clipboard support
    if (window.AndroidBridge) {
      try {
        if (!navigator.clipboard) navigator.clipboard = {};
        var origWrite = navigator.clipboard.writeText ? navigator.clipboard.writeText.bind(navigator.clipboard) : null;
        navigator.clipboard.writeText = function(text) {
          try {
            window.AndroidBridge.copyToClipboard(String(text == null ? '' : text));
            return Promise.resolve();
          } catch (e) {
            if (origWrite) return origWrite(text);
            return Promise.reject(e);
          }
        };
      } catch (e) {}

      // 2. Bridge screen wakeLock for long PDF OCR tasks
      try {
        if (!navigator.wakeLock) {
          navigator.wakeLock = {
            request: function() {
              try { window.AndroidBridge.setKeepScreenOn(true); } catch (e) {}
              return Promise.resolve({
                release: function() {
                  try { window.AndroidBridge.setKeepScreenOn(false); } catch (e) {}
                  return Promise.resolve();
                }
              });
            }
          };
        }
      } catch (e) {}

      // 3. Bridge Blob / Data URL <a download="..."> clicks so exports save to Android Downloads
      try {
        var origClick = HTMLAnchorElement.prototype.click;
        HTMLAnchorElement.prototype.click = function() {
          var dl = this.getAttribute('download') || this.download;
          var href = this.href || '';
          if (dl && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0)) {
            var fileName = String(dl || 'download.json');
            if (href.indexOf('data:') === 0) {
              var comma = href.indexOf(',');
              var meta = href.slice(5, comma);
              var mime = meta.split(';')[0] || 'application/octet-stream';
              var isB64 = meta.indexOf(';base64') >= 0;
              var raw = href.slice(comma + 1);
              var b64 = isB64 ? raw : btoa(unescape(encodeURIComponent(decodeURIComponent(raw))));
              window.AndroidBridge.saveBase64File(fileName, mime, b64);
              return;
            }
            fetch(href)
              .then(function(r) { return r.blob(); })
              .then(function(blob) {
                var reader = new FileReader();
                reader.onloadend = function() {
                  var res = String(reader.result || '');
                  var idx = res.indexOf(',');
                  var b64Data = idx >= 0 ? res.slice(idx + 1) : res;
                  window.AndroidBridge.saveBase64File(
                    fileName,
                    blob.type || 'application/octet-stream',
                    b64Data
                  );
                };
                reader.readAsDataURL(blob);
              })
              .catch(function() {
                origClick.apply(this, arguments);
              });
            return;
          }
          return origClick.apply(this, arguments);
        };
      } catch (e) {}

      // 4. Bridge window.claude.use('sample') to GeminiService when GEMINI_API_KEY is configured
      try {
        window.__aiCallbacks = window.__aiCallbacks || {};
        window.__onAndroidAiJson = function(reqId, jsonStr, errStr) {
          var cb = window.__aiCallbacks[reqId];
          if (!cb) return;
          delete window.__aiCallbacks[reqId];
          if (errStr) {
            var err = new Error(errStr);
            if (errStr === 'not_granted') err.code = 'not_granted';
            cb.rej(err);
          } else {
            try {
              cb.res(JSON.parse(jsonStr));
            } catch (e) {
              cb.rej(e);
            }
          }
        };
        if (!window.claude) {
          window.claude = {
            use: function() {
              try {
                if (!window.AndroidBridge.hasGeminiKey()) {
                  return Promise.reject({ code: 'not_granted' });
                }
                return Promise.resolve({
                  json: function(prompt) {
                    return new Promise(function(res, rej) {
                      var reqId = 'req_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                      window.__aiCallbacks[reqId] = { res: res, rej: rej };
                      window.AndroidBridge.aiJson(reqId, String(prompt || ''));
                    });
                  }
                });
              } catch (e) {
                return Promise.reject(e);
              }
            }
          };
        }
      } catch (e) {}
    }
  })();
  """.trimIndent()

class AndroidWebBridge(
  private val context: Context,
  private val webViewProvider: () -> WebView?,
  private val scope: CoroutineScope,
  private val geminiService: GeminiService,
) {
  private val kvDir: File by lazy {
    File(context.filesDir, "cbt_kv_store").apply { if (!exists()) mkdirs() }
  }

  private fun keyFile(key: String): File {
    val encoded =
      Base64.encodeToString(
        key.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
      )
    return File(kvDir, "$encoded.json")
  }

  @JavascriptInterface
  fun kvKeys(): String {
    return try {
      val arr = JSONArray()
      kvDir.listFiles()?.forEach { file ->
        if (file.isFile && file.name.endsWith(".json")) {
          val b64 = file.name.removeSuffix(".json")
          val decoded =
            String(
              Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
              Charsets.UTF_8,
            )
          arr.put(decoded)
        }
      }
      arr.toString()
    } catch (e: Exception) {
      "[]"
    }
  }

  @JavascriptInterface
  fun kvGet(key: String): String? {
    return try {
      val file = keyFile(key)
      if (file.exists()) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
      null
    }
  }

  @JavascriptInterface
  fun kvPut(key: String, jsonVal: String?): Boolean {
    return try {
      val file = keyFile(key)
      if (jsonVal == null || jsonVal == "null" || jsonVal == "undefined") {
        if (file.exists()) file.delete()
      } else {
        file.writeText(jsonVal, Charsets.UTF_8)
      }
      true
    } catch (e: Exception) {
      false
    }
  }

  @JavascriptInterface
  fun hasGeminiKey(): Boolean {
    return geminiService.hasValidApiKey()
  }

  @JavascriptInterface
  fun aiJson(requestId: String, prompt: String) {
    scope.launch {
      val result = withContext(Dispatchers.IO) { geminiService.generateJson(prompt) }
      val wv = webViewProvider() ?: return@launch
      val reqQuoted = JSONObject.quote(requestId)
      result.fold(
        onSuccess = { jsonText ->
          val jsonQuoted = JSONObject.quote(jsonText)
          wv.evaluateJavascript(
            "window.__onAndroidAiJson && window.__onAndroidAiJson($reqQuoted, $jsonQuoted, null);",
            null,
          )
        },
        onFailure = { err ->
          val errQuoted = JSONObject.quote(err.message ?: "error")
          wv.evaluateJavascript(
            "window.__onAndroidAiJson && window.__onAndroidAiJson($reqQuoted, null, $errQuoted);",
            null,
          )
        },
      )
    }
  }

  @JavascriptInterface
  fun copyToClipboard(text: String) {
    scope.launch(Dispatchers.Main) {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      clipboard?.setPrimaryClip(ClipData.newPlainText("CBT Copy", text))
    }
  }

  @JavascriptInterface
  fun setKeepScreenOn(keepOn: Boolean) {
    scope.launch(Dispatchers.Main) { webViewProvider()?.keepScreenOn = keepOn }
  }

  @JavascriptInterface
  fun saveBase64File(fileName: String, mimeType: String, base64Data: String) {
    scope.launch {
      val cleanName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "cbt_export.json" }
      val resolvedMime =
        when {
          mimeType.isNotBlank() -> mimeType
          cleanName.endsWith(".html", ignoreCase = true) -> "text/html"
          cleanName.endsWith(".json", ignoreCase = true) -> "application/json"
          else -> "application/octet-stream"
        }
      val saveResult =
        withContext(Dispatchers.IO) {
          runCatching {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              val resolver = context.contentResolver
              val values =
                ContentValues().apply {
                  put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                  put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                  put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
              val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                  ?: error("MediaStore insert returned null")
              resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                ?: error("Could not open output stream")
            } else {
              @Suppress("DEPRECATION")
              val dir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
              if (!dir.exists()) dir.mkdirs()
              val outFile = File(dir, cleanName)
              FileOutputStream(outFile).use { out -> out.write(bytes) }
            }
            cleanName
          }
        }
      saveResult.fold(
        onSuccess = { savedName ->
          Toast.makeText(
              context,
              context.getString(R.string.saved_to_downloads, savedName),
              Toast.LENGTH_LONG,
            )
            .show()
        },
        onFailure = { e ->
          Toast.makeText(
              context,
              context.getString(R.string.save_failed, e.localizedMessage ?: "Error"),
              Toast.LENGTH_LONG,
            )
            .show()
        },
      )
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CbtAppScreen(onExitApp: () -> Unit) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val geminiService = remember { GeminiService() }

  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
  var jsDialogState by remember { mutableStateOf<JsDialogState?>(null) }

  val fileChooserLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val callback = fileUploadCallback
      fileUploadCallback = null
      if (callback == null) return@rememberLauncherForActivityResult

      if (result.resultCode != Activity.RESULT_OK) {
        callback.onReceiveValue(null)
        return@rememberLauncherForActivityResult
      }

      val data = result.data
      val uris = mutableListOf<Uri>()
      if (data != null) {
        val clipData = data.clipData
        if (clipData != null) {
          for (i in 0 until clipData.itemCount) {
            clipData.getItemAt(i)?.uri?.let { uris.add(it) }
          }
        } else {
          data.data?.let { uris.add(it) }
        }
      }
      callback.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
    }

  val assetLoader = remember {
    WebViewAssetLoader.Builder()
      .setDomain("appassets.androidplatform.net")
      .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
      .build()
  }

  BackHandler(enabled = true) {
    val wv = webViewRef
    if (wv == null) {
      onExitApp()
      return@BackHandler
    }
    val backScript =
      """
      (function() {
        var adm = document.getElementById('admModal');
        if (adm) { adm.remove(); return 'handled'; }
        var modal = document.getElementById('modal');
        if (modal && !modal.classList.contains('hidden')) {
          modal.classList.add('hidden');
          modal.innerHTML = '';
          return 'handled';
        }
        var backBtn = document.querySelector('#app [data-act="back"]');
        if (backBtn) {
          backBtn.click();
          return 'handled';
        }
        return 'root';
      })();
      """.trimIndent()
    wv.evaluateJavascript(backScript) { result ->
      val clean = result?.trim('"') ?: "root"
      if (clean != "handled") {
        if (wv.canGoBack()) {
          wv.goBack()
        } else {
          onExitApp()
        }
      }
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize().background(CbtDarkBg),
    containerColor = CbtDarkBg,
    contentWindowInsets = WindowInsets.safeDrawing,
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(CbtDarkBg)
          .padding(innerPadding)
    ) {
      AndroidView(
        modifier = Modifier.fillMaxSize().testTag("cbt_webview"),
        factory = { ctx ->
          WebView(ctx).apply {
            layoutParams =
              ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
              )
            setBackgroundColor(AndroidColor.parseColor("#1A1930"))

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            settings.apply {
              javaScriptEnabled = true
              domStorageEnabled = true
              allowFileAccess = true
              allowContentAccess = true
              mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
              mediaPlaybackRequiresUserGesture = false
              javaScriptCanOpenWindowsAutomatically = true
              loadWithOverviewMode = true
              useWideViewPort = true
              builtInZoomControls = false
              displayZoomControls = false
              textZoom = 100
              cacheMode = WebSettings.LOAD_DEFAULT
            }

            addJavascriptInterface(
              AndroidWebBridge(
                context = ctx,
                webViewProvider = { webViewRef },
                scope = coroutineScope,
                geminiService = geminiService,
              ),
              "AndroidBridge",
            )

            webViewClient =
              object : WebViewClient() {
                override fun shouldInterceptRequest(
                  view: WebView,
                  request: WebResourceRequest,
                ): WebResourceResponse? {
                  val url = request.url
                  if (
                    url.host == "appassets.androidplatform.net" &&
                      url.path == "/assets/index.html"
                  ) {
                    return try {
                      WebResourceResponse(
                        "text/html",
                        "UTF-8",
                        200,
                        "OK",
                        mapOf("Access-Control-Allow-Origin" to "*"),
                        ctx.assets.open("index.html"),
                      )
                    } catch (e: Exception) {
                      assetLoader.shouldInterceptRequest(url)
                    }
                  }
                  return assetLoader.shouldInterceptRequest(url)
                }

                override fun onPageStarted(
                  view: WebView?,
                  url: String?,
                  favicon: android.graphics.Bitmap?,
                ) {
                  super.onPageStarted(view, url, favicon)
                  view?.evaluateJavascript(BRIDGE_INIT_JS, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                  super.onPageFinished(view, url)
                  view?.evaluateJavascript(BRIDGE_INIT_JS, null)
                }

                override fun onRenderProcessGone(
                  view: WebView?,
                  detail: RenderProcessGoneDetail?,
                ): Boolean {
                  view?.reload()
                  return true
                }
              }

            webChromeClient =
              object : WebChromeClient() {
                override fun onShowFileChooser(
                  webView: WebView?,
                  filePathCallback: ValueCallback<Array<Uri>>?,
                  fileChooserParams: FileChooserParams?,
                ): Boolean {
                  fileUploadCallback?.onReceiveValue(null)
                  fileUploadCallback = filePathCallback

                  val allowMultiple =
                    fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                  val acceptTypes =
                    fileChooserParams
                      ?.acceptTypes
                      ?.flatMap { it.split(",") }
                      ?.map { it.trim() }
                      ?.filter { it.isNotEmpty() }
                      .orEmpty()

                  val mimeTypes =
                    acceptTypes
                      .mapNotNull { token ->
                        when {
                          token.contains("/") -> token
                          token.equals(".pdf", ignoreCase = true) -> "application/pdf"
                          token.equals(".json", ignoreCase = true) -> "application/json"
                          token.equals(".txt", ignoreCase = true) -> "text/plain"
                          token.equals(".csv", ignoreCase = true) -> "text/csv"
                          else -> null
                        }
                      }
                      .distinct()

                  val intent =
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                      addCategory(Intent.CATEGORY_OPENABLE)
                      type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
                      if (mimeTypes.size > 1) {
                        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                      }
                      putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    }

                  return try {
                    fileChooserLauncher.launch(
                      Intent.createChooser(intent, ctx.getString(R.string.file_chooser_title))
                    )
                    true
                  } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    false
                  }
                }

                override fun onJsAlert(
                  view: WebView?,
                  url: String?,
                  message: String?,
                  result: JsResult?,
                ): Boolean {
                  if (result == null) return false
                  jsDialogState = JsDialogState.Alert(message.orEmpty(), result)
                  return true
                }

                override fun onJsConfirm(
                  view: WebView?,
                  url: String?,
                  message: String?,
                  result: JsResult?,
                ): Boolean {
                  if (result == null) return false
                  jsDialogState = JsDialogState.Confirm(message.orEmpty(), result)
                  return true
                }

                override fun onJsPrompt(
                  view: WebView?,
                  url: String?,
                  message: String?,
                  defaultValue: String?,
                  result: JsPromptResult?,
                ): Boolean {
                  if (result == null) return false
                  jsDialogState =
                    JsDialogState.Prompt(
                      message = message.orEmpty(),
                      defaultValue = defaultValue.orEmpty(),
                      result = result,
                    )
                  return true
                }
              }

            webViewRef = this
            loadUrl(APP_URL)
          }
        },
        update = { webViewRef = it },
      )

      JsDialogHost(
        dialogState = jsDialogState,
        onDismiss = { jsDialogState = null },
      )
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      fileUploadCallback?.onReceiveValue(null)
      fileUploadCallback = null
    }
  }
}

@Composable
private fun JsDialogHost(
  dialogState: JsDialogState?,
  onDismiss: () -> Unit,
) {
  when (val state = dialogState) {
    is JsDialogState.Alert -> {
      AlertDialog(
        onDismissRequest = {
          state.result.cancel()
          onDismiss()
        },
        title = { Text(stringResource(R.string.dialog_alert_title)) },
        text = { Text(state.message) },
        confirmButton = {
          TextButton(
            onClick = {
              state.result.confirm()
              onDismiss()
            },
            modifier = Modifier.testTag("js_alert_ok_button"),
          ) {
            Text(stringResource(R.string.dialog_ok))
          }
        },
      )
    }

    is JsDialogState.Confirm -> {
      AlertDialog(
        onDismissRequest = {
          state.result.cancel()
          onDismiss()
        },
        title = { Text(stringResource(R.string.dialog_confirm_title)) },
        text = { Text(state.message) },
        confirmButton = {
          TextButton(
            onClick = {
              state.result.confirm()
              onDismiss()
            },
            modifier = Modifier.testTag("js_confirm_ok_button"),
          ) {
            Text(stringResource(R.string.dialog_ok))
          }
        },
        dismissButton = {
          TextButton(
            onClick = {
              state.result.cancel()
              onDismiss()
            },
            modifier = Modifier.testTag("js_confirm_cancel_button"),
          ) {
            Text(stringResource(R.string.dialog_cancel))
          }
        },
      )
    }

    is JsDialogState.Prompt -> {
      var inputText by remember(state) { mutableStateOf(state.defaultValue) }
      AlertDialog(
        onDismissRequest = {
          state.result.cancel()
          onDismiss()
        },
        title = { Text(stringResource(R.string.dialog_prompt_title)) },
        text = {
          Column(modifier = Modifier.fillMaxWidth()) {
            Text(
              text = state.message,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
              value = inputText,
              onValueChange = { inputText = it },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("js_prompt_input"),
            )
          }
        },
        confirmButton = {
          TextButton(
            onClick = {
              state.result.confirm(inputText)
              onDismiss()
            },
            modifier = Modifier.testTag("js_prompt_ok_button"),
          ) {
            Text(stringResource(R.string.dialog_ok))
          }
        },
        dismissButton = {
          TextButton(
            onClick = {
              state.result.cancel()
              onDismiss()
            },
            modifier = Modifier.testTag("js_prompt_cancel_button"),
          ) {
            Text(stringResource(R.string.dialog_cancel))
          }
        },
      )
    }

    null -> Unit
  }
}
