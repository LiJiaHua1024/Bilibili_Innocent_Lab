/* Module-owned adapter for the official subtitle rasterizer, on its existing worker. */
globalThis.__bilSubtitleStyle = (() => {
    const file = __BIL_STYLE_FILE__;
    const fonts = new Map();
    const channels = (rgb, alpha) => [((rgb >>> 16) & 255) / 255, ((rgb >>> 8) & 255) / 255, (rgb & 255) / 255, alpha / 100];
    function read() {
        try {
            const data = cron.Data.createFromFile(file);
            if (!data) return null;
            const raw = data.getSubString();
            if (raw.length > 2048) return null;
            const value = JSON.parse(raw);
            const integer = (key, low, high) => Number.isInteger(value[key]) && value[key] >= low && value[key] <= high;
            if (value.version !== 1 || !integer('size', 50, 250) ||
                !integer('text', 0, 0xffffff) || !integer('background', 0, 0xffffff) || !integer('outline', 0, 0xffffff) ||
                !integer('textOpacity', 0, 100) || !integer('backgroundOpacity', 0, 100) || !integer('outlineOpacity', 0, 100) ||
                !integer('outlineWidth', 0, 60) || typeof value.bold !== 'boolean') return null;
            return value;
        } catch (_) { return null; }
    }
    return {
        apply(renderer) {
            // Called after all official size, density and layout defaults have been assigned.
            const value = read();
            renderer.__bilStyle = value;
            renderer._canvas.fakeBoldText = false;
            if (!value) return;
            const nativeFont = renderer._fontFn;
            renderer._fontFn = size => {
                const path = value.fontPath;
                if (typeof path !== 'string' || !/^innocent-subtitle\/font\.(ttf|otf|ttc)$/.test(path)) return nativeFont(size);
                const key = value.font + ':' + size;
                if (fonts.has(key)) return fonts.get(key);
                const font = cron.Font.createFromFile(path, size);
                if (!font) return nativeFont(size);
                if (fonts.size >= 16) fonts.delete(fonts.keys().next().value);
                fonts.set(key, font);
                return font;
            };
            renderer.fontSize = renderer.fontSize * value.size / 100;
            renderer._canvas.font = renderer._fontFn(renderer.fontSize * renderer.factor);
            renderer._canvas.fakeBoldText = value.bold;
            renderer.textColor = channels(value.text, value.textOpacity);
            renderer.strokeWidth = value.outlineWidth / 10;
            renderer.hideBg = value.backgroundOpacity === 0;
            renderer.bgPadding = Math.max(renderer.bgPadding, value.outlineWidth / 10 + 1);
        },
        color(renderer, role, fallback) {
            const value = renderer.__bilStyle;
            if (!value) return fallback;
            return role === 'background' ? channels(value.background, value.backgroundOpacity) : channels(value.outline, value.outlineOpacity);
        }
    };
})();
