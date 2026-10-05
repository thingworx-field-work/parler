/** Browser shims for Node `util` — esbuild aliases `util` for codec / transitive deps in the TW bundle. */

export function format(template, ...args) {
  let i = 0;
  return String(template).replace(/%[sdj%]/g, (m) => {
    if (m === "%%") return "%";
    const v = args[i++];
    if (m === "%d") return String(Number(v));
    if (m === "%j") return JSON.stringify(v);
    return v === undefined || v === null ? "" : String(v);
  });
}

/** Subset of Node `util.inherits` — enough for `readable-stream` / WS stack in the bundle. */
export function inherits(ctor, superCtor) {
  if (!superCtor) {
    return;
  }
  ctor.super_ = superCtor;
  ctor.prototype = Object.create(superCtor.prototype, {
    constructor: {
      value: ctor,
      enumerable: false,
      writable: true,
      configurable: true,
    },
  });
}

const util = { format, inherits };
export default util;
