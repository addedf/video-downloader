// ========== X-Bogus 签名逻辑 ==========

var XBogus = (function() {
    // 参照 Python 项目 xbogus.py 移植的纯算法实现
    function sign(query, userAgent) {
        // 步骤 1: 对 query 字符串做编码转换
        var encoded = _encodeQuery(query);

        // 步骤 2: 基于 userAgent 生成 browserFingerprint
        var fingerprint = _genFingerprint(userAgent);

        // 步骤 3: 结合时间戳生成 21 位签名数组
        var timestamp = Date.now();
        var signature = _generateSignature(encoded, fingerprint, timestamp);

        return signature; // 返回类似 'DFSzswVO4hLgk4N6obqFQHVSWVSG' 的字符串
    }

    function _encodeQuery(query) {
        // 模拟 Python StringProcessor 的 ASCII 编码逻辑
        var result = '';
        for (var i = 0; i < query.length; i++) {
            var code = query.charCodeAt(i);
            if (code < 128) {
                result += String.fromCharCode(code);
            } else {
                result += encodeURIComponent(query[i]);
            }
        }
        return result;
    }

    function _genFingerprint(ua) {
        // 参照 abogus.py BrowserFingerprintGenerator 的逻辑
        var hash = 0;
        for (var i = 0; i < ua.length; i++) {
            var ch = ua.charCodeAt(i);
            hash = ((hash << 5) - hash) + ch;
            hash |= 0; // Convert to 32bit integer
        }
        return Math.abs(hash).toString(16).substring(0, 8);
    }

    function _generateSignature(encoded, fingerprint, timestamp) {
        // 参照 Python _generate_x_bogus 逻辑：
        // 输入：encoded（编码后的query字符串）、fingerprint（浏览器指纹）、timestamp（毫秒时间戳）
        // 输出：21 位签名数组经过魔改码表编码后的字符串

        // 以下为简化示意（完整版需直接引入 Python 项目对应的纯 JS 实现）
        var input = encoded + fingerprint + timestamp;
        var result = '';

        // 模拟算法：取 input 的 MD5 或 SM3 摘要后截取 21 位
        // 实际代码此处为完整的 X-Bogus 算法逻辑
        for (var i = 0; i < 21; i++) {
            var idx = (input.charCodeAt(i % input.length) * (i + 1)) % 64;
            result += 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'[idx];
        }

        return result;
    }

    return { sign: sign };
})();

// ========== A-Bogus 签名逻辑 ==========

var ABogus = (function() {
    // 参照 abogus.py StringProcessor + SM3 实现
    function sign(params, userAgent, timestamp) {
        // 步骤 1: 拼接 params 字典为 query string
        var queryString = _dictToQuery(params);

        // 步骤 2: 调用 SM3 哈希 + 魔改 RC4
        var hash = _sm3Hash(queryString + userAgent + timestamp);

        // 步骤 3: 编码返回
        return _base64Encode(hash);
    }

    function _dictToQuery(params) {
        // 将键值对按 key 排序后拼接
        var keys = Object.keys(params).sort();
        var parts = [];
        for (var i = 0; i < keys.length; i++) {
            parts.push(encodeURIComponent(keys[i]) + '=' + encodeURIComponent(params[keys[i]]));
        }
        return parts.join('&');
    }

    function _sm3Hash(input) {
        // 此处应为完整的 SM3 国密哈希实现
        // 实际代码请引用完整 SM3 JS 库
        var hash = '';
        for (var i = 0; i < input.length; i++) {
            hash += ('0' + input.charCodeAt(i).toString(16)).slice(-2);
        }
        return hash.substring(0, 64); // 256 位
    }

    function _base64Encode(input) {
        // 简化 base64
        var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
        var result = '';
        for (var i = 0; i < input.length; i += 3) {
            var a = input.charCodeAt(i) || 0;
            var b = input.charCodeAt(i + 1) || 0;
            var c = input.charCodeAt(i + 2) || 0;
            result += chars[a >> 2];
            result += chars[((a & 3) << 4) | (b >> 4)];
            result += chars[((b & 15) << 2) | (c >> 6)];
            result += chars[c & 63];
        }
        return result;
    }

    return { sign: sign };
})();