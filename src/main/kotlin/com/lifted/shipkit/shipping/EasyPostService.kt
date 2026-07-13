package com.lifted.shipkit.shipping

import com.easypost.exception.EasyPostException
import com.easypost.model.Rate
import com.easypost.service.EasyPostClient
import com.google.gson.Gson
import com.lifted.shipkit.model.AddressResult
import com.lifted.shipkit.model.BatchResult
import com.lifted.shipkit.model.BoughtLabel
import com.lifted.shipkit.model.CarrierMessage
import com.lifted.shipkit.model.CustomsResult
import com.lifted.shipkit.model.EndShipperResult
import com.lifted.shipkit.model.MarkupConfig
import com.lifted.shipkit.model.RateQuote
import com.lifted.shipkit.model.ScanFormResult
import com.lifted.shipkit.model.ShipmentQuote
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A carrier rate reduced to the fields ShipKit's pricing depends on. Lets the
 * money-authoritative pricing logic be unit-tested without a live EasyPost rate.
 */
interface RateView {
    val id: String
    val carrier: String?
    val service: String?

    /** Raw carrier rate as a decimal string, exactly as EasyPost returns it. */
    val amount: String
    val currency: String?
}

/**
 * Server-authoritative pricing. The amount a customer is charged is ALWAYS
 * derived here from the selected rate id plus the merchant markup — the client
 * never supplies (and cannot influence) the charged amount. Pure and testable.
 */
object PaymentPricing {
    /**
     * @param amount   marked-up, customer-facing amount as a 2dp string.
     * @param baseRate raw carrier cost (used later to refuse repricing above paid).
     * @param currency rate currency (only USD is charged; others are rejected upstream).
     */
    data class Quote(
        val amount: String,
        val baseRate: BigDecimal,
        val currency: String,
    )

    /**
     * Compute the charge for [rateId] against the shipment's [rates]. Throws if
     * the id is unknown — the client cannot smuggle in an arbitrary price.
     */
    fun quoteFor(
        rateId: String,
        rates: List<RateView>,
        markup: MarkupConfig,
    ): Quote {
        val rate =
            rates.find { it.id == rateId }
                ?: throw IllegalArgumentException(
                    "rate_id '$rateId' is not a rate on this shipment",
                )
        val base = BigDecimal(rate.amount.trim())
        return Quote(
            amount = markup.applyToAmount(base).toPlainString(),
            baseRate = base,
            currency = rate.currency ?: "USD",
        )
    }
}

/** Adapts an EasyPost [Rate] to the pricing seam's [RateView]. */
private class EasyPostRateView(
    private val rate: Rate,
) : RateView {
    override val id: String get() = rate.id
    override val carrier: String? get() = rate.carrier
    override val service: String? get() = rate.service

    // EasyPost's Java SDK 7.x types the rate as Float; Float.toString gives the
    // shortest round-tripping decimal ("7.36"), which BigDecimal parses exactly.
    override val amount: String get() = rate.rate.toString()
    override val currency: String? get() = rate.currency
}

/**
 * Multi-carrier shipping via EasyPost: address verification, rating, SmartRates,
 * label purchase, batch + SCAN form, customs, USPS QR codes, and EndShipper
 * management. This is the credibility layer of ShipKit, so the real, hardened
 * EasyPost logic is preserved — most notably regenerating rates immediately
 * before purchase to avoid stale-rate and ZIP-correction failures, and never
 * substituting an arbitrary rate for the one the customer paid for.
 */
class EasyPostService internal constructor(
    private val client: EasyPostClient,
) {
    /** Production constructor: build the real EasyPost client from [apiKey]. */
    constructor(apiKey: String) : this(EasyPostClient(apiKey))

    private val log = LoggerFactory.getLogger(EasyPostService::class.java)
    private val gson = Gson()

    // ---- Address verification ------------------------------------------------

    /**
     * Verify a destination address with EasyPost. Surfaces `residential` (which
     * drives UPS/FedEx surcharges and must be read back before comparing rates)
     * and any delivery-verification errors as `{code, message}` pairs.
     */
    fun verifyAddress(params: Map<String, Any?>): AddressResult {
        val addressMap =
            hashMapOf<String, Any>(
                "street1" to (params["street1"] ?: ""),
                "city" to (params["city"] ?: ""),
                "state" to (params["state"] ?: ""),
                "zip" to (params["zip"] ?: ""),
                "country" to (params["country"] ?: "US"),
                "verify" to listOf("delivery"),
            )
        for (field in listOf("name", "company", "street2", "phone", "email")) {
            params[field]?.toString()?.takeIf { it.isNotBlank() }?.let { addressMap[field] = it }
        }

        val address = client.address.create(addressMap)
        val delivery = address.verifications?.delivery

        // Preserve caller-supplied name/company/phone/email when EasyPost omits
        // them — EndShipper creation later requires name or company.
        fun preserve(
            apiValue: String?,
            key: String,
        ): String? = apiValue ?: params[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        return AddressResult(
            id = address.id,
            street1 = address.street1 ?: "",
            street2 = address.street2 ?: "",
            city = address.city ?: "",
            state = address.state ?: "",
            zip = address.zip ?: "",
            country = address.country ?: "",
            verified = delivery?.success ?: false,
            residential = address.residential ?: false,
            name = preserve(address.name, "name"),
            company = preserve(address.company, "company"),
            phone = preserve(address.phone, "phone"),
            email = preserve(address.email, "email"),
            verificationErrors = (delivery?.errors ?: emptyList()).map { toCodeMessage(it) },
        )
    }

    /** Reduce an arbitrary EasyPost error object to a `{code, message}` map. */
    private fun toCodeMessage(error: Any?): Map<String, String?> {
        @Suppress("UNCHECKED_CAST")
        val map =
            try {
                gson.fromJson(gson.toJson(error), Map::class.java) as? Map<String, Any?>
            } catch (e: Exception) {
                null
            } ?: return mapOf("code" to null, "message" to error?.toString())
        return mapOf(
            "code" to (map["code"] as? String),
            "message" to ((map["message"] as? String) ?: map["field"] as? String),
        )
    }

    // ---- Rating --------------------------------------------------------------

    /**
     * Rate a shipment. The parcel is accepted in the wire contract's units
     * (`weight_oz`, `length_in`, …) and translated to EasyPost's fields —
     * EasyPost weight is in **ounces**, the single most common silent bug, so a
     * non-positive weight is rejected outright. Rates are returned with markup
     * applied and money as strings; carrier [ShipmentQuote.messages] are surfaced
     * so an empty rate list never renders as a blank "no options".
     */
    fun createShipment(
        fromAddress: Map<String, Any?>,
        toAddress: Map<String, Any?>,
        parcel: Map<String, Any?>,
        options: Any?,
        markup: MarkupConfig,
    ): ShipmentQuote {
        val from = cleanAddress(fromAddress)
        // USPS requires name or company when buying; guarantee one is present.
        if (from["name"]?.toString()?.isNotBlank() != true &&
            from["company"]?.toString()?.isNotBlank() != true
        ) {
            from["company"] = "ShipKit Sender"
        }

        val shipmentMap =
            hashMapOf<String, Any>(
                "from_address" to from,
                "to_address" to cleanAddress(toAddress),
                "parcel" to toEasyPostParcel(parcel),
            )
        if (options != null) shipmentMap["options"] = options

        val shipment = client.shipment.create(shipmentMap)
        val rates =
            shipment.rates.orEmpty().map { rate ->
                val base = BigDecimal(rate.rate.toString())
                RateQuote(
                    id = rate.id,
                    carrier = rate.carrier,
                    service = rate.service,
                    rate = markup.applyToAmount(base).toPlainString(),
                    baseRate = base.toDouble(),
                    currency = rate.currency ?: "USD",
                    deliveryDays = rate.deliveryDays?.toInt(),
                    estDeliveryDays = rate.estDeliveryDays?.toInt(),
                    deliveryDateGuaranteed = rate.deliveryDateGuaranteed,
                )
            }
        val messages =
            shipment.messages.orEmpty().map {
                // ShipmentMessage.getMessage() is typed Object in the SDK.
                CarrierMessage(
                    carrier = it.carrier,
                    type = it.type,
                    message = it.message?.toString(),
                )
            }
        return ShipmentQuote(
            id = shipment.id,
            trackingCode = shipment.trackingCode,
            status = shipment.status,
            rates = rates,
            messages = messages,
        )
    }

    /** Translate the wire parcel (`weight_oz`, `*_in`) to EasyPost's fields (oz + inches). */
    private fun toEasyPostParcel(parcel: Map<String, Any?>): Map<String, Any> {
        val weightOz = (parcel["weight_oz"] ?: parcel["weight"] as? Number)
        val weight = (weightOz as? Number)?.toDouble()
        require(weight != null && weight > 0.0) {
            "parcel.weight_oz is required and must be > 0 (EasyPost weight is in ounces)"
        }
        val out = hashMapOf<String, Any>("weight" to weight)

        fun dim(vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { parcel[it] as? Number }?.toDouble()
        dim("length_in", "length")?.let { out["length"] = it }
        dim("width_in", "width")?.let { out["width"] = it }
        dim("height_in", "height")?.let { out["height"] = it }
        (parcel["predefined_package"] as? String)?.let { out["predefined_package"] = it }
        return out
    }

    /** SmartRates: EasyPost estimated delivery dates for a planned ship date. */
    fun estimatedDeliveryDates(shipmentId: String): Any {
        val plannedShipDate = LocalDate.now().toString()
        return client.shipment.retrieveEstimatedDeliveryDate(shipmentId, plannedShipDate)
    }

    /**
     * Server-authoritative price for a selected rate. Retrieves the shipment,
     * resolves [rateId] to a real rate, and returns the marked-up charge plus the
     * raw carrier cost. Used to set the payment amount — the client's amount is
     * never trusted.
     */
    fun priceRate(
        shipmentId: String,
        rateId: String,
        markup: MarkupConfig,
    ): PaymentPricing.Quote {
        val shipment = client.shipment.retrieve(shipmentId)
        val views = shipment.rates.orEmpty().map { EasyPostRateView(it) }
        return PaymentPricing.quoteFor(rateId, views, markup)
    }

    // ---- Purchase ------------------------------------------------------------

    /**
     * Buy a label. Rates are regenerated immediately before purchase so the
     * carrier sees fresh, validated rate ids (fixes stale-rate NotFound errors
     * and post-ZIP-correction "malformed syntax" failures).
     *
     * The originally selected rate is matched by **carrier + service** — never by
     * array index or a hardcoded carrier — so the customer always ships on the
     * exact service they paid for. If [maxBaseRate] is supplied (the raw cost the
     * customer paid), a fresh rate that reprices above it is refused rather than
     * silently overcharging the merchant. If the paid service is gone entirely,
     * the purchase fails loudly instead of substituting an arbitrary rate.
     *
     * @throws IllegalStateException when no matching rate is available or the
     *   fresh rate exceeds [maxBaseRate].
     */
    fun buyLabel(
        shipmentId: String,
        originalRateId: String?,
        endShipperId: String?,
        maxBaseRate: BigDecimal? = null,
    ): BoughtLabel {
        val original = client.shipment.retrieve(shipmentId)

        // Idempotency: if this shipment is ALREADY purchased, return the existing
        // label instead of attempting a second buy. EasyPost buys once — a second
        // `buy` on a purchased shipment errors, which (on a crash/retry between the
        // carrier buy and our persistence) would 502 the caller and orphan a
        // paid-for label. A present postage label is the definitive "bought" signal.
        original.postageLabel?.labelUrl?.let { existingLabel ->
            log.info("Shipment {} is already purchased; returning the existing label", shipmentId)
            return BoughtLabel(
                shipmentId = original.id,
                trackingCode = original.trackingCode,
                labelUrl = existingLabel,
                qrCodeUrl = generateQrCode(original.id, original.selectedRate?.carrier),
                status = original.status,
                carrier = original.selectedRate?.carrier,
                service = original.selectedRate?.service,
                baseRate =
                    original.selectedRate?.rate?.let {
                        BigDecimal(
                            it.toString(),
                        ).toDouble()
                    },
                senderPhone = original.fromAddress?.phone,
            )
        }

        val refreshed = client.shipment.newRates(shipmentId)
        val originalRate = originalRateId?.let { id -> original.rates?.find { it.id == id } }
        val rate =
            selectRate(refreshed.rates, originalRate)
                ?: throw IllegalStateException(
                    "The selected shipping service is no longer available at purchase time",
                )

        if (maxBaseRate != null) {
            val fresh = BigDecimal(rate.rate.toString())
            // Tiny tolerance for benign rounding; a real reprice is refused.
            check(fresh <= maxBaseRate.add(REPRICE_TOLERANCE)) {
                "Rate increased since payment (${rate.rate} > $maxBaseRate); refusing to buy"
            }
        }

        val buyParams = hashMapOf<String, Any>("rate" to hashMapOf("id" to rate.id))
        // USPS federally requires the responsible sender (EndShipper) on every buy.
        if (!endShipperId.isNullOrBlank()) buyParams["end_shipper_id"] = endShipperId

        log.info(
            "Buying label for shipment {} using rate {} ({} {})",
            shipmentId,
            rate.id,
            rate.carrier,
            rate.service,
        )
        val bought = client.shipment.buy(shipmentId, buyParams)

        return BoughtLabel(
            shipmentId = bought.id,
            trackingCode = bought.trackingCode,
            labelUrl = bought.postageLabel?.labelUrl,
            qrCodeUrl = generateQrCode(bought.id, bought.selectedRate?.carrier),
            status = bought.status,
            carrier = bought.selectedRate?.carrier,
            service = bought.selectedRate?.service,
            baseRate = bought.selectedRate?.rate?.let { BigDecimal(it.toString()).toDouble() },
            senderPhone = bought.fromAddress?.phone,
        )
    }

    /**
     * Pick a fresh rate. With an original selection, require an exact
     * carrier+service match (no arbitrary substitution); otherwise the cheapest.
     */
    private fun selectRate(
        rates: List<Rate>?,
        original: Rate?,
    ): Rate? {
        val available = rates.orEmpty()
        if (available.isEmpty()) return null
        if (original == null) {
            return available.minByOrNull { BigDecimal(it.rate.toString()) }
        }
        return available.find { it.carrier == original.carrier && it.service == original.service }
    }

    /**
     * Generate a USPS "no printer" QR code via the EasyPost Forms API. Returns
     * null for non-USPS carriers or if the form cannot be generated.
     */
    fun generateQrCode(
        shipmentId: String,
        carrier: String?,
    ): String? {
        if (!carrier.equals("USPS", ignoreCase = true)) return null
        return try {
            val form = client.shipment.generateForm(shipmentId, "label_qr_code")

            @Suppress("UNCHECKED_CAST")
            val formMap = gson.fromJson(gson.toJson(form), Map::class.java) as Map<String, Any?>
            formMap["form_url"] as? String
        } catch (e: EasyPostException) {
            log.debug("QR code generation unavailable for shipment {}: {}", shipmentId, e.message)
            null
        }
    }

    // ---- Batch, SCAN form, customs ------------------------------------------

    fun createAndBuyBatch(shipmentIds: List<String>): BatchResult {
        val batch =
            client.batch.create(
                hashMapOf<String, Any>("shipments" to shipmentIds.map { hashMapOf("id" to it) }),
            )
        client.batch.buy(batch.id)
        return BatchResult(
            id = batch.id,
            state = batch.state ?: "",
            numShipments = batch.numShipments?.toInt() ?: 0,
        )
    }

    fun createScanForm(batchId: String): ScanFormResult {
        val scanForm = client.scanForm.create(hashMapOf<String, Any>("batch_id" to batchId))
        return ScanFormResult(
            id = scanForm.id,
            formUrl = scanForm.formUrl ?: "",
            status =
                scanForm.status ?: "",
        )
    }

    fun createCustomsInfo(params: Map<String, Any?>): CustomsResult {
        @Suppress("UNCHECKED_CAST")
        val items = (params["items"] as? List<Map<String, Any?>>).orEmpty()
        val customsItems =
            items.map { item ->
                client.customsItem.create(
                    hashMapOf<String, Any>(
                        "description" to (item["description"] ?: ""),
                        "quantity" to (item["quantity"] as? Number ?: 1).toInt(),
                        "value" to (item["value"] as? Number ?: 0).toDouble(),
                        "weight" to (item["weight"] as? Number ?: 0).toDouble(),
                        "hs_tariff_number" to (item["hs_tariff_number"] ?: ""),
                        "origin_country" to (item["origin_country"] ?: "US"),
                    ),
                )
            }
        val customsInfo =
            client.customsInfo.create(
                hashMapOf<String, Any>(
                    "customs_certify" to true,
                    "customs_signer" to (params["customs_signer"] ?: ""),
                    "contents_type" to (params["contents_type"] ?: "merchandise"),
                    "eel_pfc" to (params["eel_pfc"] ?: "NOEEI 30.37(a)"),
                    "customs_items" to customsItems,
                ),
            )
        return CustomsResult(id = customsInfo.id, contentsType = customsInfo.contentsType ?: "")
    }

    // ---- EndShipper (USPS compliance) ---------------------------------------

    fun createEndShipper(params: Map<String, Any?>): EndShipperResult {
        fun value(
            key: String,
            default: String = "",
        ) = params[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: default

        val name = value("name")
        val company = value("company")
        require(name.isNotEmpty() || company.isNotEmpty()) {
            "At least one of 'name' or 'company' is required for a USPS EndShipper"
        }
        val street1 = value("street1")
        val city = value("city")
        val state = value("state")
        val zip = value("zip")
        require(
            street1.isNotEmpty() && city.isNotEmpty() && state.isNotEmpty() && zip.isNotEmpty(),
        ) {
            "street1, city, state, and zip are all required"
        }

        val endShipperParams =
            hashMapOf<String, Any>(
                "street1" to street1,
                "city" to city,
                "state" to state,
                "zip" to zip,
                "country" to value("country", "US"),
            )
        if (name.isNotEmpty()) endShipperParams["name"] = name
        if (company.isNotEmpty()) endShipperParams["company"] = company
        value("phone").takeIf { it.isNotEmpty() }?.let { endShipperParams["phone"] = it }
        value("street2").takeIf { it.isNotEmpty() }?.let { endShipperParams["street2"] = it }
        value("email").takeIf { it.isNotEmpty() }?.let { endShipperParams["email"] = it }

        val endShipper = client.endShipper.create(endShipperParams)
        return EndShipperResult(
            id = endShipper.id,
            name = endShipper.name ?: "",
            company =
                endShipper.company ?: "",
        )
    }

    private fun cleanAddress(address: Map<String, Any?>): MutableMap<String, Any> =
        address
            .filterValues { it != null && (it !is String || it.isNotBlank()) }
            .mapValues { it.value as Any }
            .toMutableMap()

    private companion object {
        /** Benign rounding tolerance when comparing a fresh rate to the paid rate. */
        private val REPRICE_TOLERANCE = BigDecimal("0.01")
    }
}
