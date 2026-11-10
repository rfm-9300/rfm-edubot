package com.rfm.edubot.integrations.aws

import io.ktor.http.Url
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwsSigV4Test {

    @Test
    fun `signs AWS's IAM example the way AWS's documentation does`() {
        val headers = AwsSigV4.sign(
            method = "GET",
            url = Url("https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08"),
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=utf-8"),
            payloadSha256 = AwsSigV4.EMPTY_SHA256,
            credentials = AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
            region = "us-east-1",
            service = "iam",
            at = Instant.parse("2015-08-30T12:36:00Z"),
        )
        assertEquals("20150830T123600Z", headers["X-Amz-Date"])
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, " +
                "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7",
            headers["Authorization"],
        )
    }

    @Test
    fun `signs S3's GetObject example, sending the payload hash S3 wants`() {
        val headers = AwsSigV4.sign(
            method = "GET",
            url = Url("https://examplebucket.s3.amazonaws.com/test.txt"),
            headers = mapOf("Range" to "bytes=0-9"),
            payloadSha256 = AwsSigV4.EMPTY_SHA256,
            credentials = AwsCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
            region = "us-east-1",
            service = "s3",
            at = Instant.parse("2013-05-24T00:00:00Z"),
        )
        assertEquals(AwsSigV4.EMPTY_SHA256, headers["x-amz-content-sha256"])
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            headers["Authorization"],
        )
    }

    @Test
    fun `encodes the way SigV4 wants and names the port only when it isn't the scheme's`() {
        assertEquals("a%20b%2Bc~d", AwsSigV4.uriEncode("a b+c~d"))
        assertEquals("inbound/x%40y", AwsSigV4.uriEncode("inbound/x@y", keepSlash = true))
        val local = AwsSigV4.sign("GET", Url("http://127.0.0.1:8099/b/k"), emptyMap(), AwsSigV4.EMPTY_SHA256, AwsCredentials("a", "b"), "eu-west-1", "s3", Instant.parse("2026-01-01T00:00:00Z"))
        assertTrue(local.getValue("Authorization").contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
    }

    @Test
    fun `never prints the secret`() {
        assertEquals("AwsCredentials(accessKeyId=AKID)", AwsCredentials("AKID", "secret").toString())
    }
}
