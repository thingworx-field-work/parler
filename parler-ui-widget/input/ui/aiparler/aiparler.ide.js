(function(widgetName, isIDE) {
    const widgets = isIDE ? TW.IDE.Widgets : TW.Runtime.Widgets;
    widgets[widgetName] = function() {
        const config = TW.Widget.widgetWrapper.config(widgetName);
        TW.Widget.widgetWrapper.inject(config.elementName, this, config, isIDE);

        //[ custom code

        //]
    };

    const config = TW.Widget.widgetWrapper.config(widgetName);
    TW.Widget.widgetWrapper.loadImports(config.imports);
}('aiparler', true));
