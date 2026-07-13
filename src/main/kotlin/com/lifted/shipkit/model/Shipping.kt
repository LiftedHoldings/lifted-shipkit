package com.lifted.shipkit.model

/** Result of an EasyPost address verification. */
data class AddressResult(
    val id: String,
    val street1: String = "",
    val street2: String = "",
    val city: String = "",
    val state: String = "",
    val zip: String = "",
    val country: String = "",
    val verified: Boolean = false,
    val residential: Boolean = false,
    val name: String? = null,
    val company: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val verificationErrors: List<Map<String, String?>> = emptyList(),
)

/**
 * A single carrier rate. [rate] is the customer-facing price (base carrier rate
 * plus ShipKit markup) rendered as a decimal **string** — money is never
 * surfaced as a binary `Double`, matching EasyPost's own string convention.
 * [baseRate] is the raw carrier cost the merchant pays (kept server-side for the
 * reprice-above-paid guard, not part of the wire contract).
 */
data class RateQuote(
    val id: String,
    val carrier: String,
    val service: String,
    val rate: String,
    val baseRate: Double,
    val currency: String = "USD",
    val deliveryDays: Int? = null,
    val estDeliveryDays: Int? = null,
    val deliveryDateGuaranteed: Boolean? = null,
)

/** A carrier message accompanying a rate response (e.g. lane not served). */
data class CarrierMessage(
    val carrier: String? = null,
    val type: String? = null,
    val message: String? = null,
)

/**
 * A rated shipment plus its marked-up rate options. [messages] carries any
 * carrier errors: an empty [rates] list with populated [messages] means the
 * carrier rejected the shipment (bad account, over weight, lane not served) —
 * the frontend must surface it rather than showing a blank "no options".
 */
data class ShipmentQuote(
    val id: String,
    val trackingCode: String? = null,
    val status: String? = null,
    val rates: List<RateQuote> = emptyList(),
    val messages: List<CarrierMessage> = emptyList(),
)

/** A bought label returned from the carrier. */
data class BoughtLabel(
    val shipmentId: String,
    val trackingCode: String? = null,
    val labelUrl: String? = null,
    val qrCodeUrl: String? = null,
    val status: String? = null,
    val carrier: String? = null,
    val service: String? = null,
    val baseRate: Double? = null,
    val senderPhone: String? = null,
)

/** Result of creating a reusable EasyPost EndShipper (USPS label compliance). */
data class EndShipperResult(
    val id: String,
    val name: String = "",
    val company: String = "",
)

/** Result of creating a carrier batch and buying its labels. */
data class BatchResult(
    val id: String,
    val state: String = "",
    val numShipments: Int = 0,
)

/** Result of creating a USPS SCAN form for a batch. */
data class ScanFormResult(
    val id: String,
    val formUrl: String = "",
    val status: String = "",
)

/** Result of creating customs info for an international shipment. */
data class CustomsResult(
    val id: String,
    val contentsType: String = "",
)
