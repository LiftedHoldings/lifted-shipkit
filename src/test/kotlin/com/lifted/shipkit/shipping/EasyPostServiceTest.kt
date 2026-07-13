package com.lifted.shipkit.shipping

import com.easypost.model.Address
import com.easypost.model.AddressVerification
import com.easypost.model.AddressVerifications
import com.easypost.model.Batch
import com.easypost.model.CustomsInfo
import com.easypost.model.EndShipper
import com.easypost.model.PostageLabel
import com.easypost.model.Rate
import com.easypost.model.ScanForm
import com.easypost.model.Shipment
import com.easypost.model.ShipmentMessage
import com.easypost.service.AddressService
import com.easypost.service.BatchService
import com.easypost.service.CustomsInfoService
import com.easypost.service.CustomsItemService
import com.easypost.service.EasyPostClient
import com.easypost.service.EndShipperService
import com.easypost.service.ScanformService
import com.easypost.service.ShipmentService
import com.lifted.shipkit.model.MarkupConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [EasyPostService] against a mocked EasyPost client. Pins the API_MASTERY
 * invariants: weight is in ounces and `> 0`; carrier money is parsed via
 * BigDecimal from the rate string; a label is bought only on the exact
 * carrier+service paid for; and a rate that reprices above what the customer
 * paid is refused rather than silently overcharging the merchant.
 */
class EasyPostServiceTest {
    private val from =
        mapOf(
            "name" to "Sender",
            "street1" to "1 A St",
            "city" to "Denver",
            "state" to "CO",
            "zip" to "80202",
        )
    private val to =
        mapOf(
            "name" to "Recipient",
            "street1" to "2 B St",
            "city" to "Austin",
            "state" to "TX",
            "zip" to "78701",
        )

    /** Build a service whose EasyPost client exposes the given mocked service fields. */
    private fun serviceWith(vararg fields: Pair<String, Any>): EasyPostService {
        // The `EasyPostClient` service accessors are public final fields; construct
        // a real client and swap the needed fields for mocks so no network is hit.
        val client = EasyPostClient("ep_test_key")
        fields.forEach { (name, mock) ->
            val f = EasyPostClient::class.java.getField(name)
            f.isAccessible = true
            f.set(client, mock)
        }
        return EasyPostService(client)
    }

    private fun rate(
        id: String,
        amount: Float,
        carrier: String = "USPS",
        service: String = "Priority",
        currency: String = "USD",
        days: Int? = 2,
    ): Rate =
        mockk<Rate>().also {
            every { it.id } returns id
            every { it.rate } returns amount
            every { it.carrier } returns carrier
            every { it.service } returns service
            every { it.currency } returns currency
            every { it.deliveryDays } returns days
            every { it.estDeliveryDays } returns days
            every { it.deliveryDateGuaranteed } returns false
        }

    // ---- Rating: weight-oz + BigDecimal parse --------------------------------

    @Test
    fun `zero, negative, and absent parcel weight are all rejected before any carrier call`() {
        val shipmentService = mockk<ShipmentService>()
        val service = serviceWith("shipment" to shipmentService)

        for (badWeight in listOf(0.0, -1.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                service.createShipment(
                    from,
                    to,
                    mapOf("weight_oz" to badWeight),
                    null,
                    MarkupConfig.DEFAULT,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.createShipment(from, to, emptyMap(), null, MarkupConfig.DEFAULT)
        }
        verify(exactly = 0) { shipmentService.create(any()) }
    }

    @Test
    fun `rate string is parsed via BigDecimal and marked up exactly to the cent`() {
        val shipmentService = mockk<ShipmentService>()
        val shipment = mockk<Shipment>()
        every { shipment.id } returns "shp_1"
        every { shipment.trackingCode } returns null
        every { shipment.status } returns "unknown"
        every { shipment.rates } returns
            listOf(rate("rate_ground", 7.36f), rate("rate_exp", 48.90f))
        every { shipment.messages } returns emptyList()
        every { shipmentService.create(any()) } returns shipment

        val quote =
            serviceWith("shipment" to shipmentService).createShipment(
                from,
                to,
                mapOf("weight_oz" to 16.0, "length_in" to 6, "width_in" to 4, "height_in" to 2),
                null,
                MarkupConfig(percentageMarkup = 12.0, fixedFeeCents = 50),
            )

        assertEquals("8.74", quote.rates.first { it.id == "rate_ground" }.rate)
        assertEquals("55.27", quote.rates.first { it.id == "rate_exp" }.rate)
        assertEquals(7.36, quote.rates.first { it.id == "rate_ground" }.baseRate, 1e-9)
    }

    @Test
    fun `weight in ounces is forwarded to the carrier unchanged`() {
        val shipmentService = mockk<ShipmentService>()
        val shipment = mockk<Shipment>()
        every { shipment.id } returns "shp_2"
        every { shipment.trackingCode } returns null
        every { shipment.status } returns "unknown"
        every { shipment.rates } returns emptyList()
        every { shipment.messages } returns emptyList()
        val captured = slot<Map<String, Any>>()
        every { shipmentService.create(capture(captured)) } returns shipment

        serviceWith("shipment" to shipmentService)
            .createShipment(from, to, mapOf("weight_oz" to 12.5), null, MarkupConfig.DEFAULT)

        @Suppress("UNCHECKED_CAST")
        val parcel = captured.captured["parcel"] as Map<String, Any>
        assertEquals(12.5, parcel["weight"])
    }

    @Test
    fun `an empty rate list still surfaces carrier messages so the UI never shows a blank`() {
        val shipmentService = mockk<ShipmentService>()
        val shipment = mockk<Shipment>()
        val message =
            mockk<ShipmentMessage>().also {
                every { it.carrier } returns "USPS"
                every { it.type } returns "rate_error"
                every { it.message } returns "Lane not served"
            }
        every { shipment.id } returns "shp_3"
        every { shipment.trackingCode } returns null
        every { shipment.status } returns "unknown"
        every { shipment.rates } returns emptyList()
        every { shipment.messages } returns listOf(message)
        every { shipmentService.create(any()) } returns shipment

        val quote =
            serviceWith("shipment" to shipmentService)
                .createShipment(from, to, mapOf("weight_oz" to 8.0), null, MarkupConfig.DEFAULT)

        assertTrue(quote.rates.isEmpty())
        assertEquals(1, quote.messages.size)
        assertEquals("Lane not served", quote.messages.first().message)
    }

    // ---- Server-authoritative pricing ----------------------------------------

    @Test
    fun `priceRate resolves the selected rate id against the retrieved shipment`() {
        val shipmentService = mockk<ShipmentService>()
        val shipment = mockk<Shipment>()
        every { shipment.rates } returns
            listOf(rate("rate_ground", 7.36f), rate("rate_exp", 48.90f))
        every { shipmentService.retrieve("shp_1") } returns shipment

        val quote =
            serviceWith("shipment" to shipmentService)
                .priceRate(
                    "shp_1",
                    "rate_exp",
                    MarkupConfig(percentageMarkup = 12.0, fixedFeeCents = 50),
                )
        assertEquals("55.27", quote.amount)
        // 48.90f.toString() is "48.9"; compare by value (ignore scale).
        assertEquals(0, quote.baseRate.compareTo(BigDecimal("48.90")))
    }

    // ---- Label purchase (fresh-rate re-selection + guards) -------------------

    private fun boughtShipment(
        carrier: String = "UPS",
        service: String = "Ground",
        rate: Float = 7.36f,
    ): Shipment {
        val label =
            mockk<PostageLabel>().also {
                every { it.labelUrl } returns "https://label/1.png"
            }
        val selected = rate("rate_fresh", rate, carrier, service)
        val fromAddr = mockk<Address>().also { every { it.phone } returns "5551112222" }
        return mockk<Shipment>().also {
            every { it.id } returns "shp_1"
            every { it.trackingCode } returns "1Z999"
            every { it.postageLabel } returns label
            every { it.selectedRate } returns selected
            every { it.status } returns "purchased"
            every { it.fromAddress } returns fromAddr
        }
    }

    @Test
    fun `buyLabel rebuys the exact carrier+service originally selected`() {
        val shipmentService = mockk<ShipmentService>()
        val original = mockk<Shipment>()
        every { original.rates } returns listOf(rate("rate_orig", 7.36f, "UPS", "Ground"))
        val refreshed = mockk<Shipment>()
        every { refreshed.rates } returns
            listOf(
                rate("rate_fresh", 7.36f, "UPS", "Ground"),
                rate("rate_other", 3.0f, "USPS", "First"),
            )
        every { shipmentService.retrieve("shp_1") } returns original
        every { shipmentService.newRates("shp_1") } returns refreshed
        val buyParams = slot<Map<String, Any>>()
        every { shipmentService.buy("shp_1", capture(buyParams)) } returns boughtShipment()

        val bought =
            serviceWith("shipment" to shipmentService)
                .buyLabel(
                    "shp_1",
                    "rate_orig",
                    endShipperId = "es_1",
                    maxBaseRate = BigDecimal("7.36"),
                )

        assertEquals("https://label/1.png", bought.labelUrl)
        assertEquals("1Z999", bought.trackingCode)
        assertEquals("UPS", bought.carrier)
        // The fresh UPS/Ground rate id was used, and the EndShipper was attached.
        @Suppress("UNCHECKED_CAST")
        val rateParam = buyParams.captured["rate"] as Map<String, Any>
        assertEquals("rate_fresh", rateParam["id"])
        assertEquals("es_1", buyParams.captured["end_shipper_id"])
        // Non-USPS carrier -> no USPS QR form is generated.
        assertNull(bought.qrCodeUrl)
    }

    @Test
    fun `buyLabel refuses a fresh rate that reprices above what the customer paid`() {
        val shipmentService = mockk<ShipmentService>()
        every { shipmentService.retrieve("shp_1") } returns
            mockk<Shipment>().also {
                every { it.rates } returns
                    listOf(rate("rate_orig", 7.36f, "UPS", "Ground"))
            }
        every { shipmentService.newRates("shp_1") } returns
            mockk<Shipment>().also {
                every { it.rates } returns
                    listOf(rate("rate_fresh", 9.00f, "UPS", "Ground"))
            }

        val ex =
            assertThrows(IllegalStateException::class.java) {
                serviceWith("shipment" to shipmentService)
                    .buyLabel(
                        "shp_1",
                        "rate_orig",
                        endShipperId = null,
                        maxBaseRate = BigDecimal("7.36"),
                    )
            }
        assertTrue(ex.message!!.contains("Rate increased"))
        verify(exactly = 0) { shipmentService.buy(any(), any<Map<String, Any>>()) }
    }

    @Test
    fun `buyLabel fails loudly when the paid service is no longer offered`() {
        val shipmentService = mockk<ShipmentService>()
        every { shipmentService.retrieve("shp_1") } returns
            mockk<Shipment>().also {
                every { it.rates } returns
                    listOf(rate("rate_orig", 7.36f, "UPS", "Ground"))
            }
        every { shipmentService.newRates("shp_1") } returns
            mockk<Shipment>().also {
                every { it.rates } returns
                    listOf(rate("rate_x", 5.0f, "USPS", "First"))
            }

        assertThrows(IllegalStateException::class.java) {
            serviceWith(
                "shipment" to shipmentService,
            ).buyLabel("shp_1", "rate_orig", endShipperId = null)
        }
    }

    @Test
    fun `buyLabel with no prior selection picks the cheapest fresh rate`() {
        val shipmentService = mockk<ShipmentService>()
        every { shipmentService.retrieve("shp_1") } returns
            mockk<Shipment>().also { every { it.rates } returns emptyList() }
        every { shipmentService.newRates("shp_1") } returns
            mockk<Shipment>().also {
                every { it.rates } returns
                    listOf(
                        rate("rate_cheap", 3.0f, "USPS", "First"),
                        rate("rate_pricey", 9.0f, "UPS", "Ground"),
                    )
            }
        val buyParams = slot<Map<String, Any>>()
        // Bought carrier is non-USPS so the (unstubbed) USPS QR-form path is skipped;
        // this test only asserts which fresh rate id was chosen (the cheapest).
        every { shipmentService.buy("shp_1", capture(buyParams)) } returns
            boughtShipment(rate = 3.0f)

        serviceWith(
            "shipment" to shipmentService,
        ).buyLabel("shp_1", originalRateId = null, endShipperId = null)

        @Suppress("UNCHECKED_CAST")
        val rateParam = buyParams.captured["rate"] as Map<String, Any>
        assertEquals("rate_cheap", rateParam["id"])
    }

    // ---- EndShipper / customs / batch / scanform -----------------------------

    @Test
    fun `createEndShipper requires a name-or-company and a full address`() {
        val esService = mockk<EndShipperService>()
        val created = mockk<EndShipper>()
        every { created.id } returns "es_1"
        every { created.name } returns "Sender"
        every { created.company } returns ""
        every { esService.create(any()) } returns created
        val service = serviceWith("endShipper" to esService)

        val result =
            service.createEndShipper(
                mapOf(
                    "name" to "Sender",
                    "street1" to "1 A",
                    "city" to "Denver",
                    "state" to "CO",
                    "zip" to "80202",
                ),
            )
        assertEquals("es_1", result.id)

        // No name AND no company -> rejected.
        assertThrows(IllegalArgumentException::class.java) {
            service.createEndShipper(
                mapOf(
                    "street1" to "1 A",
                    "city" to "Denver",
                    "state" to "CO",
                    "zip" to "80202",
                ),
            )
        }
        // Missing address fields -> rejected.
        assertThrows(IllegalArgumentException::class.java) {
            service.createEndShipper(mapOf("name" to "Sender"))
        }
    }

    @Test
    fun `createCustomsInfo builds items and returns the info id`() {
        val itemService = mockk<CustomsItemService>()
        every { itemService.create(any()) } returns mockk(relaxed = true)
        val infoService = mockk<CustomsInfoService>()
        every { infoService.create(any()) } returns
            mockk<CustomsInfo>().also {
                every { it.id } returns "cstinfo_1"
                every { it.contentsType } returns "merchandise"
            }

        val result =
            serviceWith(
                "customsItem" to itemService,
                "customsInfo" to infoService,
            ).createCustomsInfo(
                mapOf(
                    "contents_type" to "merchandise",
                    "customs_signer" to "Jane",
                    "items" to
                        listOf(
                            mapOf(
                                "description" to "Widget",
                                "quantity" to 2,
                                "value" to 20.0,
                                "weight" to 3.0,
                            ),
                        ),
                ),
            )
        assertEquals("cstinfo_1", result.id)
        verify(exactly = 1) { itemService.create(any()) }
    }

    @Test
    fun `createAndBuyBatch creates then buys the batch`() {
        val batchService = mockk<BatchService>()
        val batch =
            mockk<Batch>().also {
                every { it.id } returns "batch_1"
                every { it.state } returns "created"
                every { it.numShipments } returns 2
            }
        every { batchService.create(any()) } returns batch
        every { batchService.buy("batch_1") } returns batch

        val result =
            serviceWith(
                "batch" to batchService,
            ).createAndBuyBatch(listOf("shp_1", "shp_2"))
        assertEquals("batch_1", result.id)
        assertEquals(2, result.numShipments)
        verify(exactly = 1) { batchService.buy("batch_1") }
    }

    @Test
    fun `createScanForm returns the form url`() {
        val scanService = mockk<ScanformService>()
        every { scanService.create(any()) } returns
            mockk<ScanForm>().also {
                every { it.id } returns "sf_1"
                every { it.formUrl } returns "https://sf/1"
                every { it.status } returns "created"
            }
        val result = serviceWith("scanForm" to scanService).createScanForm("batch_1")
        assertEquals("https://sf/1", result.formUrl)
    }

    // ---- Address verification ------------------------------------------------

    @Test
    fun `verifyAddress surfaces residential and the verified flag`() {
        val addressService = mockk<AddressService>()
        val delivery =
            mockk<AddressVerification>().also {
                every { it.success } returns true
                every { it.errors } returns emptyList()
            }
        val verifications =
            mockk<AddressVerifications>().also {
                every { it.delivery } returns
                    delivery
            }
        val address =
            mockk<Address>(relaxed = true).also {
                every { it.id } returns "adr_1"
                every { it.street1 } returns "1 A St"
                every { it.city } returns "Denver"
                every { it.state } returns "CO"
                every { it.zip } returns "80202"
                every { it.country } returns "US"
                every { it.residential } returns true
                every { it.verifications } returns verifications
            }
        every { addressService.create(any()) } returns address

        val result =
            serviceWith("address" to addressService)
                .verifyAddress(
                    mapOf(
                        "street1" to "1 A St",
                        "city" to "Denver",
                        "state" to "CO",
                        "zip" to "80202",
                    ),
                )

        assertTrue(result.verified)
        assertTrue(result.residential)
        assertEquals("Denver", result.city)
    }
}
