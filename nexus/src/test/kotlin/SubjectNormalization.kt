import org.junit.Test
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.extractReservePubFromSubject

class SubjectNormalization {

    @Test
    fun testBeforeAndAfter() {
        val mereValue = "1ENVZ6EYGB6Z509KRJ6E59GK1EQXZF8XXNY9SN33C2KDGSHV9KA0"
        assert(mereValue == extractReservePubFromSubject(mereValue))
        assert(mereValue == extractReservePubFromSubject("noise before ${mereValue} noise after"))
        val mereValueNewLines = "\t1ENVZ6EYGB6Z\n\n\n509KRJ6E59GK1EQXZF8XXNY9\nSN33C2KDGSHV9KA0"
        assert(mereValue == extractReservePubFromSubject(mereValueNewLines))
        assert(mereValue == extractReservePubFromSubject("noise before $mereValueNewLines noise after"))
    }

    /**
     * Here we test whether the value that the extractor picks
     * from a payment subjects is then validated by the crypto backend.
     */
    @Test
    fun extractorVsDecoder() {
        val validPub = "7R422Z6C5TPG0JM32KRWV093J0AG0GVZV1247F9PBSFZT6Y61G1G"
        assert(CryptoUtil.checkValidEddsaPublicKey(validPub))
        // Swapping zeros with Os.
        assert(CryptoUtil.checkValidEddsaPublicKey(validPub.replace('0', 'O')))
        // At this point, the decoder handles 0s and Os interchangeably.
        // Now check that the reserve pub. extractor behaves equally.
        val extractedPub = extractReservePubFromSubject(validPub) // has 0s.
        // The "!!" ensures that the extractor found a likely reserve pub.
        assert(CryptoUtil.checkValidEddsaPublicKey(extractedPub!!))
        val extractedPubWithOs = extractReservePubFromSubject(validPub.replace('0', 'O'))
        // The "!!" ensures that the extractor did find the reserve pub. with Os instead of zeros.
        assert(CryptoUtil.checkValidEddsaPublicKey(extractedPubWithOs!!))
    }
}