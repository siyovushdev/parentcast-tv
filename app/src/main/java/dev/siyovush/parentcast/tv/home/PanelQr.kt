package dev.siyovush.parentcast.tv.home

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.common.BitMatrix
import java.net.URI

internal fun panelQrMatrix(url: String): BitMatrix {
    val uri = URI(url)
    require(uri.scheme == "http" && uri.host != null && uri.port == 8765)
    require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path == "/")
    return QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 320, 320)
}
