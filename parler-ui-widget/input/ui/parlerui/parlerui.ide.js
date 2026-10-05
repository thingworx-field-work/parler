(function(widgetName, isIDE) {
    const widgets = isIDE ? TW.IDE.Widgets : TW.Runtime.Widgets;

    function installThemeBridge(widget, config) {
        let previousHost;
        let previousTokens = [];
        let previousSignature;

        function tokenExpressions() {
            const variants = Array.isArray(config.styleDict) ? config.styleDict : [];
            const variant = variants.find(function(entry) {
                return entry && entry.variant === '';
            });
            const parts = variant && Array.isArray(variant.parts) ? variant.parts : [];
            const hostPart = parts.find(function(entry) {
                return entry && entry.part === '';
            });
            const states = hostPart && Array.isArray(hostPart.states) ? hostPart.states : [];
            const entries = [];

            states.forEach(function(state) {
                const styles = state && state.styles && typeof state.styles === 'object'
                    ? state.styles
                    : {};
                Object.keys(styles).forEach(function(tokenName) {
                    const match = /^\$\{([^}]+)\}$/.exec(styles[tokenName]);
                    if (tokenName.indexOf('--parler-theme-') === 0) {
                        if (match) {
                            entries.push({tokenName: tokenName, expression: match[1]});
                        } else {
                            TW.log.warn(
                                'AI Parler: ignoring invalid Theme manifest entry "' + tokenName +
                                '"; expected one bare ${expression} value.'
                            );
                        }
                    }
                });
            });
            return entries;
        }

        const manifest = tokenExpressions();

        function refreshThemeTokens() {
            const host = widget.jqElement && widget.jqElement[0];
            const manager = typeof TW.getStyleManager === 'function'
                ? TW.getStyleManager()
                : null;
            if (!host || !host.style || !manager || typeof manager.resolveExpressions !== 'function') {
                return false;
            }

            let resolved;
            try {
                resolved = manager.resolveExpressions(
                    TW.currentTheme,
                    config.elementName,
                    '',
                    function() { return ''; }
                );
            } catch (err) {
                TW.log.warn('AI Parler: Theme expression resolution failed: ' + (err && err.message ? err.message : err));
                return false;
            }

            // Unknown widget/variant is an unavailable result, not an empty Theme.
            if (resolved === null || resolved === undefined) {
                return false;
            }

            const next = [];
            // Iterate token -> expression so one resolved expression fans out to every token.
            manifest.forEach(function(entry) {
                const value = resolved[entry.expression];
                if (value !== null && value !== undefined && String(value).trim() !== '') {
                    next.push({tokenName: entry.tokenName, value: String(value)});
                }
            });
            const signature = JSON.stringify(next);

            if (host !== previousHost) {
                previousHost = host;
                previousTokens = [];
                previousSignature = undefined;
            }
            if (signature === previousSignature) {
                return true;
            }

            previousTokens.forEach(function(tokenName) {
                host.style.removeProperty(tokenName);
            });
            next.forEach(function(entry) {
                host.style.setProperty(entry.tokenName, entry.value);
            });
            previousTokens = next.map(function(entry) { return entry.tokenName; });
            previousSignature = signature;
            return true;
        }

        function wrapLifecycle(name) {
            const original = widget[name];
            widget[name] = function() {
                const result = typeof original === 'function'
                    ? original.apply(this, arguments)
                    : undefined;
                refreshThemeTokens();
                return result;
            };
        }

        wrapLifecycle('afterRender');
        wrapLifecycle('afterSetProperty');
    }

    widgets[widgetName] = function() {
        const config = TW.Widget.widgetWrapper.config(widgetName);
        TW.Widget.widgetWrapper.inject(config.elementName, this, config, isIDE);
        installThemeBridge(this, config);

        //[ custom code

        //]
    };

    const config = TW.Widget.widgetWrapper.config(widgetName);
    TW.Widget.widgetWrapper.loadImports(config.imports);
}('parlerui', true));
