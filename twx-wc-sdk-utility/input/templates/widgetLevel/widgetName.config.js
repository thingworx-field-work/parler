// eslint-disable-next-line max-len
// export var config = {{{config}}};
// import { widgetWrapper } from "widgetWrapper.js"

(function(TW) {
    const widgetName = '{{{widgetName}}}';
    // eslint-disable-next-line max-len, quotes, quote-props, comma-spacing, key-spacing
    const config = {{{config}}};

    // Temporary widgetWrapper if not initialized
    TW.Widget.widgetWrapper = TW.Widget.widgetWrapper || {
        loadImports: function(imports) {
            this.imports.push(imports);
        },
        config: function(name, conf) {
            if (conf) {
                this.configs[name] = conf;
            }
            return this.configs[name];
        },
        imports: [],
        configs: {}
    };

    TW.Widget.widgetWrapper.config(widgetName, config);
}(TW));
