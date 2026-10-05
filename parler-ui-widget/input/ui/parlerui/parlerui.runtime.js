/* eslint-disable no-undef */
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
        wrapLifecycle('changeThemeName');
    }

    widgets[widgetName] = function() {
        const config = TW.Widget.widgetWrapper.config(widgetName);
        TW.Widget.widgetWrapper.inject(config.elementName, this, config, isIDE);
        installThemeBridge(this, config);

        //[ custom code
        /**
         * Mashup-bound services. Extra arguments vary by platform build; we accept
         * a bound STRING as first extra arg or common object shapes.
         */
        this.serviceInvoked = function(serviceName /* , ...args */) {
            const el = this.jqElement && this.jqElement[0];
            if (!el) {
                TW.log.warn('AI Parler: serviceInvoked("' + serviceName + '") — jqElement[0] missing');
                return;
            }
            const rest = Array.prototype.slice.call(arguments, 1);
            let payload = rest[0];
            if (payload != null && typeof payload === 'object' && !Array.isArray(payload)) {
                payload =
                    payload.Json !== undefined ? payload.Json :
                    payload.json !== undefined ? payload.json :
                    payload.ApplyLiveJson !== undefined ? payload.ApplyLiveJson :
                    payload.LoadHistoryJson !== undefined ? payload.LoadHistoryJson :
                    rest[0];
            }
            switch (serviceName) {
                case 'ConnectAndBind':
                    if (typeof el.connectAndBind === 'function') {
                        void Promise.resolve(el.connectAndBind()).catch(function(err) {
                            TW.log.error('AI Parler: ConnectAndBind promise rejected: ' + (err && err.message ? err.message : err));
                        });
                    } else {
                        TW.log.error('AI Parler: ConnectAndBind — <parler-ui> has no connectAndBind() (upgrade not complete?)');
                    }
                    break;
                case 'DisconnectAlwaysOn':
                    if (typeof el.disconnectAlwaysOn === 'function') {
                        el.disconnectAlwaysOn();
                    } else {
                        TW.log.warn('AI Parler: DisconnectAlwaysOn — disconnectAlwaysOn() missing on element');
                    }
                    break;
                case 'ApplyLiveJson':
                    if (typeof el.applyLiveJson === 'function' && payload != null) {
                        el.applyLiveJson(String(payload));
                    }
                    break;
                case 'LoadHistoryJson':
                    if (typeof el.loadHistoryJson === 'function' && payload != null) {
                        el.loadHistoryJson(String(payload));
                    }
                    break;
                case 'ResetChat':
                    if (typeof el.resetChat === 'function') {
                        el.resetChat();
                    }
                    break;
                default:
                    TW.log.error('AI Parler: unexpected service "' + serviceName + '"');
            }
        };
        //]
    };

    const config = TW.Widget.widgetWrapper.config(widgetName);
    TW.Widget.widgetWrapper.loadImports(config.imports);
}('parlerui', false));
