package org.lepotager.resiliencevault.panic

import android.telephony.PhoneNumberUtils
import java.util.Locale

sealed interface PhoneNumberNormalization {
    data class Success(val e164: String) : PhoneNumberNormalization
    data class Invalid(val reason: PhoneNumberInvalidReason) : PhoneNumberNormalization
}

enum class PhoneNumberInvalidReason {
    COUNTRY_REQUIRED,
    INVALID_COUNTRY_ISO,
    NUMBER_REQUIRED,
    INVALID_NUMBER
}

class PlatformPhoneNumberNormalizer {
    fun normalize(rawNumber: String, explicitCountryIso: String): PhoneNumberNormalization {
        val number = rawNumber.trim()
        if (number.isEmpty()) {
            return PhoneNumberNormalization.Invalid(PhoneNumberInvalidReason.NUMBER_REQUIRED)
        }

        val country = explicitCountryIso.trim().uppercase(Locale.ROOT)
        if (country.isEmpty()) {
            return PhoneNumberNormalization.Invalid(PhoneNumberInvalidReason.COUNTRY_REQUIRED)
        }
        if (!COUNTRY_ISO.matches(country)) {
            return PhoneNumberNormalization.Invalid(PhoneNumberInvalidReason.INVALID_COUNTRY_ISO)
        }

        val e164 = PhoneNumberUtils.formatNumberToE164(number, country)
            ?: return PhoneNumberNormalization.Invalid(PhoneNumberInvalidReason.INVALID_NUMBER)

        return if (RemotePanicCommand.isCanonicalE164(e164)) {
            PhoneNumberNormalization.Success(e164)
        } else {
            PhoneNumberNormalization.Invalid(PhoneNumberInvalidReason.INVALID_NUMBER)
        }
    }

    private companion object {
        val COUNTRY_ISO = Regex("^[A-Z]{2}$")
    }
}
