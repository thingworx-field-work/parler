/* eslint-disable no-undef */
(function(widgetName, isIDE) {
    const widgets = isIDE ? TW.IDE.Widgets : TW.Runtime.Widgets;
    widgets[widgetName] = function() {
        const config = TW.Widget.widgetWrapper.config(widgetName);
        TW.Widget.widgetWrapper.inject(config.elementName, this, config, isIDE);

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
}('aiparler', false));
