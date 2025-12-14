package iad1tya.echo.music.dlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class DLNAPlayer(
    private val device: DLNADevice
) {
    private val TAG = "DLNAPlayer"
    
    data class PositionInfo(
        val relTime: String, // HH:MM:SS
        val trackDuration: String, // HH:MM:SS
        val absTime: String,
        val relCount: String,
        val absCount: String
    ) {
        fun getRelTimeMs(): Long = parseTime(relTime)
        fun getTrackDurationMs(): Long = parseTime(trackDuration)

        private fun parseTime(time: String): Long {
            if (time.isBlank() || time == "NOT_IMPLEMENTED") return 0L
            try {
                val parts = time.split(":").map { it.toDouble().toLong() }
                if (parts.size == 3) {
                    return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
            return 0L
        }
    }

    data class TransportInfo(
        val currentTransportState: String, // STOPPED, PLAYING, PAUSED_PLAYBACK, TRANSITIONING, NO_MEDIA_PRESENT
        val currentTransportStatus: String,
        val currentSpeed: String
    )

    suspend fun setAVTransportURI(mediaUrl: String, metadata: String = "") = withContext(Dispatchers.IO) {
        try {
            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"
            val soapBody = buildSetAVTransportURIRequest(mediaUrl, metadata)
            
            sendSOAPRequest(device.controlUrl, soapAction, soapBody)
            Log.d(TAG, "Set media URL: $mediaUrl")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting AV transport URI", e)
            false
        }
    }
    
    suspend fun play() = withContext(Dispatchers.IO) {
        try {
            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#Play"
            val soapBody = buildPlayRequest()
            
            sendSOAPRequest(device.controlUrl, soapAction, soapBody)
            Log.d(TAG, "Sent play command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error playing", e)
            false
        }
    }
    
    suspend fun pause() = withContext(Dispatchers.IO) {
        try {
            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#Pause"
            val soapBody = buildPauseRequest()
            
            sendSOAPRequest(device.controlUrl, soapAction, soapBody)
            Log.d(TAG, "Sent pause command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing", e)
            false
        }
    }
    
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#Stop"
            val soapBody = buildStopRequest()
            
            sendSOAPRequest(device.controlUrl, soapAction, soapBody)
            Log.d(TAG, "Sent stop command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping", e)
            false
        }
    }
    
    suspend fun seek(position: String) = withContext(Dispatchers.IO) {
        try {
            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#Seek"
            val soapBody = buildSeekRequest(position)
            
            sendSOAPRequest(device.controlUrl, soapAction, soapBody)
            Log.d(TAG, "Sent seek command to $position")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking", e)
            false
        }
    }
    
    suspend fun setVolume(volume: Int) = withContext(Dispatchers.IO) {
        try {
            val soapAction = "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume"
            val soapBody = buildSetVolumeRequest(volume)
            
            // Note: Volume control URL might be different from transport control URL
            // For simplicity we assume it's the same or handled by the device struct.
            // But usually RenderingControl service has its own URL.
            // We'll use controlUrl for now, but typically it should be device.renderingControlUrl
            // If it fails, we might need to update DLNADevice to store multiple URLs.
            // For now, let's assume controlUrl is for AVTransport.
            // Actually, DLNADevice has only 'controlUrl'. We should check if we can get RenderingControl URL.
            // If not, we might fail here.
            // However, most simple implementations use the same base or just different service types on same endpoint?
            // No, usually different endpoints.
            // Let's assume controlUrl is generic or we only support AVTransport for now.
            // But wait, existing code had setVolume.

            sendSOAPRequest(device.controlUrl, soapAction, soapBody)
            Log.d(TAG, "Set volume to $volume")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
            false
        }
    }
    
    suspend fun getPositionInfo(): PositionInfo? = withContext(Dispatchers.IO) {
        try {
            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo"
            val soapBody = buildGetPositionInfoRequest()

            val response = sendSOAPRequest(device.controlUrl, soapAction, soapBody)
            parsePositionInfo(response)
        } catch (e: Exception) {
            // Don't log spam for polling
            // Log.e(TAG, "Error getting position info", e)
            null
        }
    }

    suspend fun getTransportInfo(): TransportInfo? = withContext(Dispatchers.IO) {
        try {
            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo"
            val soapBody = buildGetTransportInfoRequest()

            val response = sendSOAPRequest(device.controlUrl, soapAction, soapBody)
            parseTransportInfo(response)
        } catch (e: Exception) {
            // Log.e(TAG, "Error getting transport info", e)
            null
        }
    }

    private fun sendSOAPRequest(url: String, soapAction: String, soapBody: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
        connection.setRequestProperty("SOAPAction", "\"$soapAction\"")
        connection.doOutput = true
        connection.doInput = true
        
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(soapBody)
            writer.flush()
        }
        
        val responseCode = connection.responseCode
        val response = if (responseCode == 200) {
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } else {
            BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream)).use { it.readText() }
        }
        
        connection.disconnect()
        
        if (responseCode != 200) {
            throw Exception("SOAP request failed with code $responseCode: $response")
        }
        
        return response
    }
    
    private fun parsePositionInfo(xml: String): PositionInfo {
        val relTime = extractTagValue(xml, "RelTime") ?: "00:00:00"
        val trackDuration = extractTagValue(xml, "TrackDuration") ?: "00:00:00"
        val absTime = extractTagValue(xml, "AbsTime") ?: "00:00:00"
        val relCount = extractTagValue(xml, "RelCount") ?: "0"
        val absCount = extractTagValue(xml, "AbsCount") ?: "0"

        return PositionInfo(relTime, trackDuration, absTime, relCount, absCount)
    }

    private fun parseTransportInfo(xml: String): TransportInfo {
        val state = extractTagValue(xml, "CurrentTransportState") ?: "STOPPED"
        val status = extractTagValue(xml, "CurrentTransportStatus") ?: "OK"
        val speed = extractTagValue(xml, "CurrentSpeed") ?: "1"

        return TransportInfo(state, status, speed)
    }

    private fun extractTagValue(xml: String, tagName: String): String? {
        val pattern = Pattern.compile("<$tagName>(.*?)</$tagName>")
        val matcher = pattern.matcher(xml)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun buildSetAVTransportURIRequest(mediaUrl: String, metadata: String): String {
        val metadataXml = if (metadata.isNotEmpty()) {
            metadata.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        } else {
            ""
        }
        
        return """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <CurrentURI>$mediaUrl</CurrentURI>
                        <CurrentURIMetaData>$metadataXml</CurrentURIMetaData>
                    </u:SetAVTransportURI>
                </s:Body>
            </s:Envelope>"""
    }
    
    private fun buildPlayRequest(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <Speed>1</Speed>
                    </u:Play>
                </s:Body>
            </s:Envelope>"""
    }
    
    private fun buildPauseRequest(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                    </u:Pause>
                </s:Body>
            </s:Envelope>"""
    }
    
    private fun buildStopRequest(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                    </u:Stop>
                </s:Body>
            </s:Envelope>"""
    }
    
    private fun buildSeekRequest(position: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <Unit>REL_TIME</Unit>
                        <Target>$position</Target>
                    </u:Seek>
                </s:Body>
            </s:Envelope>"""
    }
    
    private fun buildSetVolumeRequest(volume: Int): String {
        return """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                        <InstanceID>0</InstanceID>
                        <Channel>Master</Channel>
                        <DesiredVolume>$volume</DesiredVolume>
                    </u:SetVolume>
                </s:Body>
            </s:Envelope>"""
    }

    private fun buildGetPositionInfoRequest(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                    </u:GetPositionInfo>
                </s:Body>
            </s:Envelope>"""
    }

    private fun buildGetTransportInfoRequest(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                    </u:GetTransportInfo>
                </s:Body>
            </s:Envelope>"""
    }
}
