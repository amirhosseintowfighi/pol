package studio.virgule.pol.core.billing

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Subscription model and the boundary to a payment provider.
 *
 * Billing is isolated from everything else: it never sees calls, messages, contacts, call history, pairing keys or
 * device identifiers. The only data that may cross [BillingProvider] is listed in [BillingRequest].
 *
 * No provider is configured in this repository (see docs/BILLING.md). [UnconfiguredBillingProvider] is the default
 * and it never reports an entitlement: the app does not pretend a payment succeeded, and while billing is not
 * configured the app does not lock features behind it.
 */
object Plan {
    const val ID = "pol.monthly"
    /** 500,000 Iranian tomans per month (= 5,000,000 rials). */
    const val PRICE_TOMANS = 500_000L
    const val PRICE_RIALS = PRICE_TOMANS * 10
    const val TRIAL_MONTHS = 1L

    /** Trial end: one calendar month after the start, in the user's time zone. */
    fun trialEnd(start: Instant, zone: ZoneId): Instant = ZonedDateTime.ofInstant(start, zone).plusMonths(TRIAL_MONTHS).toInstant()

    fun nextRenewal(paidFrom: Instant, zone: ZoneId): Instant = ZonedDateTime.ofInstant(paidFrom, zone).plusMonths(1).toInstant()
}

/** Entitlement as last verified by the provider. Only provider-verified states unlock anything. */
sealed interface Entitlement {
    /** No payment provider is configured in this build. */
    data object NotConfigured : Entitlement
    /** Provider reachable, the user has never subscribed: the free month is available. */
    data object EligibleForTrial : Entitlement
    data class Trial(val start: Instant, val end: Instant, val renewsAt: Instant, val priceTomans: Long, val cancelled: Boolean) : Entitlement
    data class Active(val paidUntil: Instant, val renewsAt: Instant?, val priceTomans: Long, val cancelled: Boolean) : Entitlement
    /** Card-to-card transfer reported, waiting for the provider/merchant to confirm. Grants nothing yet. */
    data class PendingVerification(val reference: String, val submittedAt: Instant) : Entitlement
    data class Expired(val endedAt: Instant) : Entitlement
    data class Error(val message: String) : Entitlement

    val unlocksPaidFeatures: Boolean
        get() = when (this) {
            is Trial -> true
            is Active -> true
            else -> false
        }
}

/**
 * The only fields any billing traffic may contain. There is deliberately no field for phone numbers, contacts,
 * messages, call history, IMEI/serial numbers, SIM data or pairing keys.
 */
data class BillingRequest(
    val planId: String,
    /** Random installation-scoped id created only for billing; not derived from any device identifier. */
    val billingAccountId: String,
    val amountRials: Long,
    /** Card-to-card only: the bank's transfer reference number (tracking code) the user typed. Never a card PIN. */
    val transferReference: String? = null,
)

interface BillingProvider {
    val name: String
    val configured: Boolean
    /** Starts a provider-hosted checkout (gateway payment). Returns a URL to open in the system browser, or null. */
    suspend fun startCheckout(request: BillingRequest): Result<String>
    /** Asks the provider for the current entitlement of [billingAccountId]. */
    suspend fun refresh(billingAccountId: String): Entitlement
    /** Card-to-card: submits a transfer reference for manual/merchant verification. */
    suspend fun submitTransfer(request: BillingRequest): Entitlement
}

class UnconfiguredBillingProvider : BillingProvider {
    override val name = "none"
    override val configured = false
    override suspend fun startCheckout(request: BillingRequest) = Result.failure<String>(IllegalStateException("No payment provider is configured in this build"))
    override suspend fun refresh(billingAccountId: String): Entitlement = Entitlement.NotConfigured
    override suspend fun submitTransfer(request: BillingRequest): Entitlement = Entitlement.NotConfigured
}

/** Card-to-card transfer references are 6–20 digits on Iranian bank receipts; anything else is refused. */
object TransferReference {
    private val re = Regex("^[0-9]{6,20}$")
    fun normalize(input: String): String? {
        val digits = input.map { ch ->
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
        }.filter { it != ' ' && it != '-' }.joinToString("")
        return digits.takeIf { re.matches(it) }
    }
}

/** What the paywall shows before the user commits: all dates and the price, computed the same way everywhere. */
data class OfferTerms(val trialStart: Instant, val trialEnd: Instant, val firstChargeAt: Instant, val priceTomans: Long) {
    companion object {
        fun startingAt(now: Instant, zone: ZoneId): OfferTerms {
            val end = Plan.trialEnd(now, zone)
            return OfferTerms(now, end, end, Plan.PRICE_TOMANS)
        }
    }
}
