/**
 * Minimal EventEmitter + `util.inherits` target for bundled stacks that expect Node-style inheritance.
 * A real ES `class` cannot be used — legacy patterns call `EventEmitter.call(this)` on subclasses.
 * Lazy `_m` in prototype methods covers callers that never invoke the constructor.
 */
function EventEmitter() {
  if (this instanceof EventEmitter || (this != null && typeof this === "object")) {
    if (!this._m) {
      this._m = new Map();
    }
    return;
  }
  return new EventEmitter();
}

function ensureMap(self) {
  if (!self._m) {
    self._m = new Map();
  }
}

EventEmitter.prototype.on = function (ev, fn) {
  ensureMap(this);
  if (!this._m.has(ev)) this._m.set(ev, []);
  this._m.get(ev).push(fn);
  return this;
};

EventEmitter.prototype.emit = function (ev, ...args) {
  ensureMap(this);
  for (const fn of this._m.get(ev) || []) {
    try {
      fn(...args);
    } catch (e) {
      console.error(e);
    }
  }
  return this;
};

EventEmitter.prototype.removeListener = function (ev, fn) {
  if (!this._m) return this;
  const a = this._m.get(ev);
  if (!a) return this;
  const i = a.indexOf(fn);
  if (i >= 0) a.splice(i, 1);
  return this;
};

EventEmitter.prototype.removeAllListeners = function (ev) {
  if (!this._m) return this;
  if (ev === undefined) this._m.clear();
  else this._m.delete(ev);
  return this;
};

export { EventEmitter };
export default EventEmitter;
