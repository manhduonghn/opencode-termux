// Code viewer bridge for CodeViewerDialog, modelled after the Acode editor: CodeMirror 5
// inside the WebView. Java calls window.editorApi.* through evaluateJavascript and this
// script talks back through the injected AndroidBridge object.
(function () {
    "use strict";

    var cm = null;
    var cleanGeneration = null;
    var meta = null;
    var fontPx = 14;

    function bridge() {
        return window.AndroidBridge;
    }

    // Bridge calls are best effort: the dialog may already be gone when they fire.
    function call(name) {
        try {
            var target = bridge();
            if (target && typeof target[name] === "function") {
                target[name].apply(null, Array.prototype.slice.call(arguments, 1));
            }
        } catch (ignored) {
            // The Java side logs its own failures; a broken bridge callback must never
            // break the editor.
        }
    }

    function extensionOf(fileName) {
        var name = String(fileName || "");
        var dot = name.lastIndexOf(".");
        return dot >= 0 ? name.slice(dot + 1).toLowerCase() : "";
    }

    function modeFor(fileName) {
        var info = null;
        try {
            info = CodeMirror.findModeByFileName(String(fileName || ""));
            if (!info) {
                var extension = extensionOf(fileName);
                if (extension) info = CodeMirror.findModeByExtension(extension);
            }
        } catch (ignored) {
            info = null;
        }
        if (!info || (!info.mode && !info.mime)) return null;
        return { spec: info.mime || info.mode, label: info.name || info.mode };
    }

    function applyFontSize(px) {
        fontPx = Math.max(8, Math.min(28, px | 0));
        if (!cm) return;
        cm.getWrapperElement().style.fontSize = fontPx + "px";
        cm.refresh();
    }

    // Keep the overscroll area the same colour as the active CodeMirror theme.
    function syncPageBackground() {
        try {
            var colour = window.getComputedStyle(cm.getWrapperElement()).backgroundColor;
            if (colour) document.body.style.background = colour;
        } catch (ignored) {
            // Cosmetic only.
        }
    }

    function markClean() {
        if (!cm) return;
        cleanGeneration = cm.changeGeneration();
        call("onDirtyChanged", false);
    }

    function reportDirty() {
        if (!cm) return;
        try {
            call("onDirtyChanged", !cm.isClean(cleanGeneration));
        } catch (ignored) {
            // Nothing useful can be done when generation tracking fails.
        }
    }

    function reportCursor() {
        if (!cm) return;
        try {
            var cursor = cm.getCursor();
            call("onCursor", cursor.line + 1, cursor.ch + 1);
        } catch (ignored) {
            // The status bar is optional.
        }
    }

    function saveShortcut() {
        call("requestSave");
        return false;
    }

    function createEditor() {
        cm = CodeMirror(document.body, {
            value: "",
            lineNumbers: true,
            autofocus: false,
            dragDrop: false,
            styleActiveLine: true,
            matchBrackets: true,
            autoCloseBrackets: true,
            autoCloseTags: true,
            indentUnit: 4,
            tabSize: 4,
            extraKeys: {
                "Ctrl-S": saveShortcut,
                "Cmd-S": saveShortcut,
                "Tab": function (editor) {
                    editor.execCommand("insertSoftTab");
                }
            }
        });
        cm.on("change", reportDirty);
        cm.on("cursorActivity", reportCursor);
    }

    window.editorApi = {
        // Set the document, mode and options. The content itself is pulled from Java
        // through AndroidBridge.fetchContent() so huge files never travel through
        // evaluateJavascript(), which is limited by the binder transaction size.
        setDocument: function (options) {
            meta = options || {};
            if (!cm) createEditor();
            var mode = modeFor(meta.fileName || "");
            cm.setOption("mode", mode ? mode.spec : "text/plain");
            cm.setOption("theme", meta.theme || "material-darker");
            cm.setOption("readOnly", !!meta.readOnly);
            cm.setOption("lineWrapping", !!meta.wrap);
            applyFontSize(meta.fontSize || 14);
            var content = "";
            try {
                content = bridge().fetchContent();
            } catch (ignored) {
                content = "";
            }
            cm.operation(function () {
                cm.setValue(content);
                cm.clearHistory();
            });
            markClean();
            cm.refresh();
            setTimeout(syncPageBackground, 0);
            call("onMode", mode ? mode.label : "Text");
            reportCursor();
        },

        setEditable: function (editable) {
            if (!cm) return;
            cm.setOption("readOnly", !editable);
            if (editable) cm.focus();
        },

        setLineWrapping: function (wrap) {
            if (!cm) return;
            cm.setOption("lineWrapping", !!wrap);
            cm.refresh();
        },

        setFontSize: function (px) {
            applyFontSize(px);
        },

        gotoLine: function (line) {
            if (!cm) return;
            var target = Math.max(0, Math.min(cm.lineCount() - 1, (line | 0) - 1));
            cm.setCursor({ line: target, ch: 0 });
            cm.scrollIntoView({ line: target, ch: 0 }, cm.defaultTextHeight() * 8);
            cm.focus();
            reportCursor();
        },

        openSearch: function () {
            if (!cm) return;
            cm.focus();
            cm.execCommand("find");
        },

        undo: function () {
            if (!cm) return;
            cm.undo();
            reportDirty();
        },

        redo: function () {
            if (!cm) return;
            cm.redo();
            reportDirty();
        },

        // Replace the document with the current content on the Java side (reload).
        reload: function () {
            if (!cm) return;
            var content = "";
            try {
                content = bridge().fetchContent();
            } catch (ignored) {
                content = "";
            }
            cm.operation(function () {
                cm.setValue(content);
                cm.clearHistory();
            });
            markClean();
        },

        // Hand the current content to Java so it can write it to disk.
        getContent: function () {
            var value = cm ? cm.getValue() : "";
            call("saveContent", value);
        },

        markClean: function () {
            markClean();
        }
    };

    window.addEventListener("error", function (event) {
        call("onError", String((event && event.message) || "script error"));
    });

    call("onReady");
})();
