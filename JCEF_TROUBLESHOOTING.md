# JCEF & Application Troubleshooting Guide

This document records critical solutions and fixes applied to make the Smart QP Print Manager (JCEF + JavaFX) function correctly, specifically concerning its interaction with modern web frameworks like Django and packaging it via `jpackage`.

## 1. JCEF Browser - Handling Javascript Prompts (Alerts/Confirmations)
**Issue:** 
Web pages relying on `window.alert`, `window.confirm`, or `window.prompt` failed to display any dialogs in the JCEF browser, causing silent failures on the frontend.

**Solution:**
JCEF does not provide default UI dialogs for these actions. We had to implement a custom `CefJSDialogHandler` to route these web prompts to native JavaFX `Alert` dialogs.

**Implementation Details (in `App.java`):**
```java
client.addJSDialogHandler(new CefJSDialogHandlerAdapter() {
    @Override
    public boolean onJSDialog(CefBrowser browser, String origin_url, JSDialogType dialog_type, String message_text, String default_prompt_text, CefJSDialogCallback callback, BoolRef suppress_message) {
        Platform.runLater(() -> {
            if (dialog_type == JSDialogType.JSDIALOGTYPE_ALERT) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION, message_text, ButtonType.OK);
                alert.showAndWait();
                callback.Continue(true, "");
            } else if (dialog_type == JSDialogType.JSDIALOGTYPE_CONFIRM) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message_text, ButtonType.YES, ButtonType.NO);
                alert.showAndWait().ifPresent(response -> {
                    callback.Continue(response == ButtonType.YES, "");
                });
            }
        });
        return true; // Indicates the dialog is being handled
    }
});
```

## 2. JCEF Browser - 403 CSRF on Page Refresh (POST Resubmission)
**Issue:**
When refreshing a page that was generated via a `POST` request (like a Django search/filter form), JCEF natively resubmitted the form. However, Chromium strips the `Origin` and `Referer` headers on native reload of POST requests. This caused Django's CSRF protection to reject the request with `Forbidden (403): CSRF verification failed`.

**Solution:**
We bypass the native Chromium reload mechanism. Instead, we capture the raw `POST` data and the current URL. When the user clicks "Refresh", we inject JavaScript into the browser to dynamically build a hidden HTML `<form>` with the captured data and submit it. This forces the browser to treat it as a fresh `POST` request, fully preserving all `Origin`/`Referer` headers required by Django.

**Implementation Details:**
1. **Capture Data:** Using `CefRequestHandlerAdapter.onBeforeBrowse`, we extract the `CefPostData` from the request. Since JCEF garbage collects `CefPostDataElement` objects quickly, we immediately read the bytes and cache them as a pure Java `String`.
2. **Inject Form:**
```java
String js = "var form = document.createElement('form'); "
          + "form.method = 'POST'; "
          + "form.action = '" + browser.getURL() + "'; "
          // parse cachedPostDataString and append hidden inputs...
          + "document.body.appendChild(form); form.submit();";
browser.executeJavaScript(js, browser.getURL(), 0);
```

## 3. JavaFX Packaging - `jpackage` and the Maven Shade Plugin
**Issue:**
When creating the executable JAR or native MSI, `jpackage` failed to locate resources, or the JavaFX runtime failed to boot with missing modules.

**Fixes Applied:**
1. **Shaded JAR:** We use `maven-shade-plugin` to bundle all Java dependencies into a single fat JAR.
2. **Absolute Paths in Batch Scripts:** `jpackage` is sensitive to working directories. We updated `build_packages.bat` to strictly change the working directory via `cd /d "%~dp0"` to avoid path resolution issues like `C:\Users\...\src\main\resources\icon.ico not found`.
3. **Empty Directories in Batch Scripts:** Make sure output and input directories (e.g. `jpackage_input`) are explicitly created via `if not exist mkdir` before attempting to copy files into them.

## 4. Encoding Issues (Copyright Symbol)
**Issue:**
The `©` symbol appeared corrupted (e.g., `Â©`) when compiled on Windows environments depending on the default file encoding.
**Solution:**
Replaced literal `©` characters in Java source files with their unicode escape sequence `\u00A9` to ensure platform-agnostic compilation.
