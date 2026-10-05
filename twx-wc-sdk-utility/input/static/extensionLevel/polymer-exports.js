// Currently we're manually specifying all needed exports from the polymer library.
// 2 options that can be checked:
// - If it can be done by rollup
// - If we can structrure the imports in the following manner:
//   let polymer_bundle = {}
//   import * as imp1 from './@polymer/polymer/polymer-element.js'
//   polymer_bundle['./@polymer/polymer/polymer-element.js'] = imp1
//   export default polymer_bundle;
//   and in the extension file the following import will be replaced by:
//   import {Polymer} from './@polymer/polymer/lib/legacy/polymer-fn.js'
//   by
//   import polymer_bundle from 'bundle.js'
//   let Polymer = polymer_bundle['./@polymer/polymer/lib/legacy/polymer-fn.js'].Polymer

export {PolymerElement, html} from './@polymer/polymer/polymer-element.js';
export * from './@polymer/iron-meta/iron-meta.js';
export * from './@polymer/iron-resizable-behavior/iron-resizable-behavior.js';
export * from './@polymer/polymer/lib/elements/array-selector.js';
export * from './@polymer/polymer/lib/elements/custom-style.js';
export * from './@polymer/polymer/lib/elements/dom-bind.js';
export * from './@polymer/polymer/lib/elements/dom-if.js';
export * from './@polymer/polymer/lib/elements/dom-module.js';
export * from './@polymer/polymer/lib/elements/dom-repeat.js';
export * from './@polymer/polymer/lib/legacy/class.js';
export * from './@polymer/polymer/lib/legacy/legacy-data-mixin.js';
export * from './@polymer/polymer/lib/legacy/legacy-element-mixin.js';
export * from './@polymer/polymer/lib/legacy/mutable-data-behavior.js';
export * from './@polymer/polymer/lib/legacy/polymer-fn.js';
export * from './@polymer/polymer/lib/legacy/polymer.dom.js';
export * from './@polymer/polymer/lib/legacy/templatizer-behavior.js';
export * from './@polymer/polymer/lib/mixins/dir-mixin.js';
export * from './@polymer/polymer/lib/mixins/disable-upgrade-mixin.js';
export * from './@polymer/polymer/lib/mixins/element-mixin.js';
export * from './@polymer/polymer/lib/mixins/gesture-event-listeners.js';
export * from './@polymer/polymer/lib/mixins/mutable-data.js';
export * from './@polymer/polymer/lib/mixins/properties-changed.js';
export * from './@polymer/polymer/lib/mixins/properties-mixin.js';
export * from './@polymer/polymer/lib/mixins/property-accessors.js';
export * from './@polymer/polymer/lib/mixins/property-effects.js';
export * from './@polymer/polymer/lib/mixins/strict-binding-parser.js';
export * from './@polymer/polymer/lib/mixins/template-stamp.js';
export * from './@polymer/polymer/lib/utils/array-splice.js';
export * from './@polymer/polymer/lib/utils/async.js';
export * from './@polymer/polymer/lib/utils/boot.js';
export * from './@polymer/polymer/lib/utils/case-map.js';
export * from './@polymer/polymer/lib/utils/debounce.js';
export * from './@polymer/polymer/lib/utils/flattened-nodes-observer.js';
// export * from './@polymer/polymer/lib/utils/flush.js';
export * from './@polymer/polymer/lib/utils/gestures.js';
export * from './@polymer/polymer/lib/utils/hide-template-controls.js';
export * from './@polymer/polymer/lib/utils/html-tag.js';
export * from './@polymer/polymer/lib/utils/mixin.js';
export * from './@polymer/polymer/lib/utils/path.js';
export * from './@polymer/polymer/lib/utils/render-status.js';
export * from './@polymer/polymer/lib/utils/resolve-url.js';
export * from './@polymer/polymer/lib/utils/scope-subtree.js';
export * from './@polymer/polymer/lib/utils/settings.js';
export * from './@polymer/polymer/lib/utils/style-gather.js';
// export * from './@polymer/polymer/lib/utils/telemetry.js';
export * from './@polymer/polymer/lib/utils/templatize.js';
export * from './@polymer/polymer/lib/utils/unresolved.js';
export * from './@polymer/polymer/lib/utils/wrap.js';
export * from './@polymer/polymer/polymer-legacy.js';

import {resetMouseCanceller} from './@polymer/polymer/lib/utils/gestures.js';
window.resetMouseCanceller = resetMouseCanceller;
