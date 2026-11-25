package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The PC orb (assets/orb/pragon_orb.html, the same page the desktop orb runs) in a WebView.
 * The floating bubble and the UI customiser both load this one page, so they share the page's
 * saved settings (orb style, colours, glow, click sound, core logo ...): change something in the
 * customiser and the bubble shows it after a reload.
 */
object OrbWeb {
    const val URL = "file:///android_asset/orb/pragon_orb.html"
    /** #tpinit = floating bubble (transparent, plays the startup animation); #noinit = menu / customiser (no animation). */
    private const val BUBBLE_URL = URL + "#tpinit"
    private const val PLAIN_URL = URL + "#noinit"

    /**
     * Floating bubble set-up. The page itself (class "tp", from #tpinit) keeps the background transparent and hides the
     * HUD; the startup animation (dot, emblem draws out, logo, blinks, expands into the orb) plays first. When the
     * page has opened into the orb, the emblem layers are removed ("done") and the orb is placed at full size.
     */
    private const val BUBBLE_INIT = """
(function(){
  var n=0,t0=Date.now(),was=false,fin=false;
  (function go(){
    if(!window.orb){ if(n++<80) setTimeout(go,100); return; }
    try{ orb.setTransparent(true); orb.setPart('bar',false); orb.move(0,0,1.0,0.01); }catch(e){}
  })();
  function done(){ if(fin) return; fin=true; document.documentElement.classList.add('done'); clearInterval(iv); }
  var iv=setInterval(function(){
    var b=document.body, c=!!(b&&b.classList.contains('ui-closed')), age=Date.now()-t0;
    if(c) was=true;
    if(was&&!c){ setTimeout(done,2800); clearInterval(iv); return; }   // opened into the orb: let the expand finish
    if(!was&&age>3000){ done(); return; }                              // no startup animation ran
    if(age>20000){ try{ frameUI.open(); }catch(e){} done(); }          // failsafe
  },150);
})();
"""


    /**
     * Radial menu overlay (opened by double-tapping the bubble): the orb with its wheel open on a transparent page.
     * A tap on a wedge calls PragonMenu.pick(name); a tap outside the wheel closes it (PragonMenu.close()).
     */
    private const val MENU_INIT = """
(function(){
  var s=document.createElement('style');
  s.textContent='html,body,#stage{background:transparent!important}#edge,#sframe,#emblem,#ecb,#ecp,.w{display:none!important}';
  document.head.appendChild(s);
  var n=0,was=false;
  (function go(){
    if(!window.orb){ if(n++<80) setTimeout(go,100); return; }
    try{ orb.setTransparent(true); orb.setPart('bar',false); }catch(e){}
    orb.onselect=function(name){ try{ PragonMenu.pick(String(name)); }catch(e){} };
    setTimeout(function(){ try{ orb.openMenu(); }catch(e){} },250);
    setInterval(function(){
      var o=false; try{ o=orb.menuOpen(); }catch(e){}
      if(o) was=true; else if(was){ was=false; try{ PragonMenu.close(); }catch(e){} }
    },120);
  })();
})();
"""

    /** Opens the page's own "UI customiser" widget (the page listens for this message). */
    private const val OPEN_CUSTOMISER = """
(function(){
  var n=0;
  (function go(){
    if(!window.orb){ if(n++<80) setTimeout(go,100); return; }
    setTimeout(function(){ window.postMessage({pragonCmd:'customiser',open:true},'*'); },300);
  })();
})();
"""

    /**
     * @param bubble true = transparent orb only (the floating bubble); false = the full page with the UI customiser opened.
     * @param menu true = the radial-menu overlay (transparent page, wheel open); needs [bridge] (exposed to the page as PragonMenu).
     * @param fileChooser lets the page's <input type=file> open Android's picker (without it the "Choose logo" button does nothing in a WebView).
     * @param onLoaded called on the main thread each time the page has finished loading (also after a reload).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        ctx: Context, bubble: Boolean, menu: Boolean = false, bridge: Any? = null,
        fileChooser: ((ValueCallback<Array<Uri>>, android.content.Intent) -> Boolean)? = null,
        onLoaded: (WebView) -> Unit = {}
    ): WebView {
        val w = WebView(ctx)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                 // the customiser saves its choices in the page's localStorage
            mediaPlaybackRequiresUserGesture = false // orb sounds
            allowFileAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
        }
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.setBackgroundColor(if (bubble || menu) Color.TRANSPARENT else Color.BLACK)
        if (bridge != null) w.addJavascriptInterface(bridge, "PragonMenu")
        // camera/microphone requests are not granted: the orb stays a plain orb
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                val h = fileChooser ?: return false
                if (cb == null || params == null) return false
                return h(cb, params.createIntent())
            }
        }
        w.webViewClient = object : WebViewClient() {
            // Stay on the orb page: nothing inside it should navigate the bubble somewhere else.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(if (menu) MENU_INIT else if (bubble) BUBBLE_INIT else OPEN_CUSTOMISER, null)
                onLoaded(view)
            }
        }
        w.loadUrl(if (bubble && !menu) BUBBLE_URL else PLAIN_URL)
        return w
    }

    /** WebView must be touched on the main thread; this is safe to call from any thread. */
    fun call(w: WebView?, js: String) {
        if (w == null) return
        val run = Runnable { try { w.evaluateJavascript("try{$js}catch(e){}", null) } catch (_: Exception) { } }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) run.run()
        else android.os.Handler(android.os.Looper.getMainLooper()).post(run)
    }
}
