// X-Bogus port based on jiji262/douyin-downloader utils/xbogus.py.
// The Android flow uses the upstream fallback strategy: detail URL + X-Bogus.

var XBogus = (function() {
    var CT = 536919696;
    var UA_KEY = [0, 1, 12];
    var RC4_KEY = [255];
    var ENCODE_TABLE = "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=";

    function sign(url, userAgent) {
        return build(url || "", userAgent || "");
    }

    function build(url, userAgent) {
        var paramsArray = generateParamsArray(url, userAgent);
        var garbled = rc4EncryptBytes(paramsArray, RC4_KEY);
        return encode([2, 255].concat(garbled));
    }

    function generateParamsArray(url, userAgent) {
        var ts = Math.floor(Date.now() / 1000);
        var urlHash = md5Encrypt(url);
        var emptyHash = hexToBytes(md5HexString(hexToBytes("d41d8cd98f00b204e9800998ecf8427e")));
        var uaHash = hexToBytes(md5HexString(toLatin1Bytes(base64EncodeBytes(rc4EncryptBytes(toLatin1Bytes(userAgent), UA_KEY)))));

        var arr = [
            64, 0.00390625, 1, 12,
            urlHash[14], urlHash[15],
            emptyHash[14], emptyHash[15],
            uaHash[14], uaHash[15],
            ts >> 24, (ts >> 16) & 255, (ts >> 8) & 255, ts & 255,
            CT >> 24, (CT >> 16) & 255, (CT >> 8) & 255, CT & 255
        ];

        var xorResult = toByte(arr[0]);
        for (var i = 1; i < arr.length; i++) {
            xorResult ^= toByte(arr[i]);
        }
        arr.push(xorResult);

        var even = [];
        var odd = [];
        for (var idx = 0; idx < arr.length; idx += 2) {
            even.push(arr[idx]);
            if (idx + 1 < arr.length) odd.push(arr[idx + 1]);
        }
        return encodingConversion(even.concat(odd));
    }

    function encodingConversion(values) {
        return [
            values[0],
            values[10],
            values[1],
            values[11],
            values[2],
            values[12],
            values[3],
            values[13],
            values[4],
            values[14],
            values[5],
            values[15],
            values[6],
            values[16],
            values[7],
            values[17],
            values[8],
            values[18],
            values[9]
        ].map(toByte);
    }

    function encode(bytes) {
        var result = "";
        for (var i = 0; i < bytes.length; i += 3) {
            var n1 = bytes[i];
            var n2 = i + 1 < bytes.length ? bytes[i + 1] : 0;
            var n3 = i + 2 < bytes.length ? bytes[i + 2] : 0;
            result += ENCODE_TABLE[n1 >> 2];
            result += ENCODE_TABLE[((n1 & 3) << 4) | (n2 >> 4)];
            result += ENCODE_TABLE[((n2 & 15) << 2) | (n3 >> 6)];
            result += ENCODE_TABLE[n3 & 63];
        }
        return result;
    }

    function rc4EncryptBytes(bytes, key) {
        var s = [];
        for (var i = 0; i < 256; i++) s[i] = i;

        var j = 0;
        for (var i2 = 0; i2 < 256; i2++) {
            j = (j + s[i2] + key[i2 % key.length]) & 255;
            swap(s, i2, j);
        }

        var out = [];
        var i3 = 0;
        j = 0;
        for (var n = 0; n < bytes.length; n++) {
            i3 = (i3 + 1) & 255;
            j = (j + s[i3]) & 255;
            swap(s, i3, j);
            out.push(bytes[n] ^ s[(s[i3] + s[j]) & 255]);
        }
        return out;
    }

    function swap(arr, i, j) {
        var tmp = arr[i];
        arr[i] = arr[j];
        arr[j] = tmp;
    }

    function md5Encrypt(input) {
        return hexToBytes(md5HexString(hexToBytes(md5PythonString(input))));
    }

    function md5PythonString(input) {
        var bytes = input.length > 32 ? toLatin1Bytes(input) : hexToBytes(input);
        return md5HexString(bytes);
    }

    function md5HexString(bytes) {
        var input = bytes.slice();
        var originalBitLength = input.length * 8;

        input.push(0x80);
        while ((input.length % 64) !== 56) {
            input.push(0);
        }

        var low = originalBitLength >>> 0;
        var high = Math.floor(originalBitLength / 4294967296) >>> 0;
        for (var i = 0; i < 4; i++) input.push((low >>> (8 * i)) & 255);
        for (var j = 0; j < 4; j++) input.push((high >>> (8 * j)) & 255);

        var a0 = 0x67452301;
        var b0 = 0xefcdab89;
        var c0 = 0x98badcfe;
        var d0 = 0x10325476;

        var s = [
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
        ];

        var k = [];
        for (var ki = 0; ki < 64; ki++) {
            k[ki] = Math.floor(Math.abs(Math.sin(ki + 1)) * 4294967296) >>> 0;
        }

        for (var offset = 0; offset < input.length; offset += 64) {
            var m = [];
            for (var mi = 0; mi < 16; mi++) {
                var base = offset + mi * 4;
                m[mi] = (
                    input[base] |
                    (input[base + 1] << 8) |
                    (input[base + 2] << 16) |
                    (input[base + 3] << 24)
                ) >>> 0;
            }

            var a = a0;
            var b = b0;
            var c = c0;
            var d = d0;

            for (var round = 0; round < 64; round++) {
                var f;
                var g;

                if (round < 16) {
                    f = (b & c) | ((~b) & d);
                    g = round;
                } else if (round < 32) {
                    f = (d & b) | ((~d) & c);
                    g = (5 * round + 1) % 16;
                } else if (round < 48) {
                    f = b ^ c ^ d;
                    g = (3 * round + 5) % 16;
                } else {
                    f = c ^ (b | (~d));
                    g = (7 * round) % 16;
                }

                var temp = d;
                d = c;
                c = b;
                b = add32(b, leftRotate(add32(add32(a, f), add32(k[round], m[g])), s[round]));
                a = temp;
            }

            a0 = add32(a0, a);
            b0 = add32(b0, b);
            c0 = add32(c0, c);
            d0 = add32(d0, d);
        }

        return wordToHex(a0) + wordToHex(b0) + wordToHex(c0) + wordToHex(d0);
    }

    function hexToBytes(hex) {
        var bytes = [];
        for (var i = 0; i < hex.length; i += 2) {
            bytes.push(parseInt(hex.substr(i, 2), 16));
        }
        return bytes;
    }

    function toLatin1Bytes(str) {
        var out = [];
        for (var i = 0; i < str.length; i++) {
            out.push(str.charCodeAt(i) & 255);
        }
        return out;
    }

    function base64EncodeBytes(bytes) {
        var chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        var result = "";
        for (var i = 0; i < bytes.length; i += 3) {
            var b1 = bytes[i];
            var b2 = i + 1 < bytes.length ? bytes[i + 1] : 0;
            var b3 = i + 2 < bytes.length ? bytes[i + 2] : 0;
            result += chars[b1 >> 2];
            result += chars[((b1 & 3) << 4) | (b2 >> 4)];
            result += i + 1 < bytes.length ? chars[((b2 & 15) << 2) | (b3 >> 6)] : "=";
            result += i + 2 < bytes.length ? chars[b3 & 63] : "=";
        }
        return result;
    }

    function leftRotate(x, c) {
        return ((x << c) | (x >>> (32 - c))) >>> 0;
    }

    function add32(a, b) {
        return (a + b) >>> 0;
    }

    function toByte(value) {
        return Math.trunc(Number(value)) & 255;
    }

    function wordToHex(word) {
        var hex = "";
        for (var i = 0; i < 4; i++) {
            hex += ("0" + ((word >>> (8 * i)) & 255).toString(16)).slice(-2);
        }
        return hex;
    }

    return { sign: sign };
})();

var ABogus = (function() {
    function sign() {
        throw new Error("A-Bogus is not implemented. Use X-Bogus fallback for aweme detail.");
    }

    return { sign: sign };
})();
