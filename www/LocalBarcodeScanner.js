var exec = require('cordova/exec');

var LocalBarcodeScanner = {
    // initScanner([macOrName], success, error)
    initScanner: function (a, b, c) {
        var macOrName, success, error;
        if (typeof a === 'string') { macOrName = a; success = b; error = c; }
        else { success = a; error = b; }
        var args = macOrName ? [macOrName] : [];
        exec(success, error, 'LocalBarcodeScanner', 'initialize', args);
    },

    // optional 3rd arg = timeout in ms (default 5000)
    scanBarcode: function (success, error, timeoutMs) {
        var args = (typeof timeoutMs === 'number') ? [timeoutMs] : [];
        exec(success, error, 'LocalBarcodeScanner', 'scan', args);
    },

    stopScanner: function (success, error) {
        exec(success, error, 'LocalBarcodeScanner', 'stop', []);
    },

    // New helpers for R6 BLE (non-breaking additions)
    scanBle: function (success, error) {
        exec(success, error, 'LocalBarcodeScanner', 'scanBle', []);
    },
    disconnect: function (success, error) {
        exec(success, error, 'LocalBarcodeScanner', 'disconnect', []);
    },
    getBatteryLevel: function (success, error) {
        exec(success, error, 'LocalBarcodeScanner', 'getBatteryLevel', []);
    }
};

module.exports = LocalBarcodeScanner;
