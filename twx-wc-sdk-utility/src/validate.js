const path = require('path');
const fs = require('fs-extra');

const args = require('./args');
const {logWithException} = require('./logger');

const validate = (props) => {
    const webCmpts = props.htmlImports;
    const widgets = props.components;

    const noWebCmpts = !Array.isArray(webCmpts) || webCmpts.length === 0;
    const noWidgets = !Array.isArray(widgets) || widgets.length === 0;

    if (args.extension) {
        if (!props.extensionName) {
            throw new Error('extensionName doesn\'t exist in widgets.json');
        }

        if (!props.version) {
            throw new Error('version doesn\'t exist in widgets.json');
        }
    }

    if (noWebCmpts && noWidgets) {
        throw new Error('Provided widgets.json doesn"t contain any widgets/webcomponents');
    }
};

/*
 * Validate if provided urls of the components actually exist in the dependencies location
 */
const validateComponentUrls = (dependencies, depsLocation) => {

    Object.keys(dependencies).forEach(el => {
        const componentUrl = dependencies[el].url;

        if (componentUrl) {
            const depLocation = path.resolve(depsLocation, componentUrl);

            if (!fs.pathExistsSync(depLocation)) {
                throw logWithException(`${componentUrl} is not found in downloaded dependencies`);
            }
        }
    });
};

module.exports.validate = validate;
module.exports.validateComponentUrls = validateComponentUrls;
