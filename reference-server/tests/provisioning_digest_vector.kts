import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

fun field(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty() && bytes.size <= 0xffff)
    return ByteBuffer.allocate(2).putShort(bytes.size.toShort()).array() + bytes
}

fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

val preimage = ByteArrayOutputStream().apply {
    write(field(ascii("RV-DELETE-PROVISIONING-REQUEST-V1")))
    write(ByteBuffer.allocate(4).putInt(1).array())
    write(field(ascii("test-primary")))
    write(field(ascii("tenant-1")))
    write(field(ascii("1".repeat(64))))
    write(field(ascii("2".repeat(64))))
    write(field(ascii("3".repeat(64))))
    write(field(ascii("opaque-v1")))
    write(field(ascii("4".repeat(64))))
}.toByteArray()

val digest = MessageDigest.getInstance("SHA-256")
    .digest(preimage)
    .joinToString("") { "%02x".format(it) }

val expected = "f22cbfa8212e00b9643eb70d0e58315129b8556bc31e2c1d06e9fb68fd197641"
check(digest == expected) {
    "Provisioning digest mismatch: expected $expected, got $digest"
}

println(digest)
