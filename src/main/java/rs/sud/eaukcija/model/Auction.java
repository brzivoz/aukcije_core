package rs.sud.eaukcija.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import rs.sud.eaukcija.sync.persistence.NormalizedPropertyKind;
import rs.sud.eaukcija.sync.persistence.SaleScope;

@Entity
@Table(name = "auctions")
public class Auction {

    @Id
    private Long id;

    private String auctionNumber;
    private Instant startDate;
    private Instant endDate;
    private Instant publicationDate;

    private BigDecimal startingPrice;
    private BigDecimal estimatedPrice;
    private BigDecimal currentPrice;
    private BigDecimal maxOfferedPrice;
    private BigDecimal bidStep;

    @Column(length = 2000)
    private String shortDescription;

    @Column(length = 4000)
    private String description;

    private String status;
    private boolean firstSale;
    private String propertyType;

    // Executor
    private String executorName;

    // Category
    private String categoryName;

    // Place / Location
    private String placeName;
    private String placeZipCode;
    private String municipality;
    private String cadastral;

    private boolean detailsFetched;

    @Column(length = 64)
    private String listingFingerprint;

    private Instant detailsFetchedAt;

    private Integer sourceDetailCategoryId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private SaleScope saleScope;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private NormalizedPropertyKind normalizedPropertyKind;

    @Column(length = 64)
    private String taxonomySha256;

    private UUID lastSuccessfulSyncRunId;

    private long absenceCount;

    private Instant lastSeenAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAuctionNumber() { return auctionNumber; }
    public void setAuctionNumber(String auctionNumber) { this.auctionNumber = auctionNumber; }

    public Instant getStartDate() { return startDate; }
    public void setStartDate(Instant startDate) { this.startDate = startDate; }

    public Instant getEndDate() { return endDate; }
    public void setEndDate(Instant endDate) { this.endDate = endDate; }

    public Instant getPublicationDate() { return publicationDate; }
    public void setPublicationDate(Instant publicationDate) { this.publicationDate = publicationDate; }

    public BigDecimal getStartingPrice() { return startingPrice; }
    public void setStartingPrice(BigDecimal startingPrice) { this.startingPrice = startingPrice; }

    public BigDecimal getEstimatedPrice() { return estimatedPrice; }
    public void setEstimatedPrice(BigDecimal estimatedPrice) { this.estimatedPrice = estimatedPrice; }

    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }

    public BigDecimal getMaxOfferedPrice() { return maxOfferedPrice; }
    public void setMaxOfferedPrice(BigDecimal maxOfferedPrice) { this.maxOfferedPrice = maxOfferedPrice; }

    public BigDecimal getBidStep() { return bidStep; }
    public void setBidStep(BigDecimal bidStep) { this.bidStep = bidStep; }

    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String shortDescription) { this.shortDescription = shortDescription; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isFirstSale() { return firstSale; }
    public void setFirstSale(boolean firstSale) { this.firstSale = firstSale; }

    public String getPropertyType() { return propertyType; }
    public void setPropertyType(String propertyType) { this.propertyType = propertyType; }

    public String getExecutorName() { return executorName; }
    public void setExecutorName(String executorName) { this.executorName = executorName; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getPlaceName() { return placeName; }
    public void setPlaceName(String placeName) { this.placeName = placeName; }

    public String getPlaceZipCode() { return placeZipCode; }
    public void setPlaceZipCode(String placeZipCode) { this.placeZipCode = placeZipCode; }

    public String getMunicipality() { return municipality; }
    public void setMunicipality(String municipality) { this.municipality = municipality; }

    public String getCadastral() { return cadastral; }
    public void setCadastral(String cadastral) { this.cadastral = cadastral; }

    public boolean isDetailsFetched() { return detailsFetched; }
    public void setDetailsFetched(boolean detailsFetched) { this.detailsFetched = detailsFetched; }

    public String getListingFingerprint() { return listingFingerprint; }
    public void setListingFingerprint(String listingFingerprint) { this.listingFingerprint = listingFingerprint; }

    public Instant getDetailsFetchedAt() { return detailsFetchedAt; }
    public void setDetailsFetchedAt(Instant detailsFetchedAt) { this.detailsFetchedAt = detailsFetchedAt; }

    public Integer getSourceDetailCategoryId() { return sourceDetailCategoryId; }
    public void setSourceDetailCategoryId(Integer sourceDetailCategoryId) {
        this.sourceDetailCategoryId = sourceDetailCategoryId;
    }

    public SaleScope getSaleScope() { return saleScope; }
    public void setSaleScope(SaleScope saleScope) { this.saleScope = saleScope; }

    public NormalizedPropertyKind getNormalizedPropertyKind() { return normalizedPropertyKind; }
    public void setNormalizedPropertyKind(NormalizedPropertyKind normalizedPropertyKind) {
        this.normalizedPropertyKind = normalizedPropertyKind;
    }

    public String getTaxonomySha256() { return taxonomySha256; }
    public void setTaxonomySha256(String taxonomySha256) { this.taxonomySha256 = taxonomySha256; }

    public UUID getLastSuccessfulSyncRunId() { return lastSuccessfulSyncRunId; }
    public void setLastSuccessfulSyncRunId(UUID lastSuccessfulSyncRunId) {
        this.lastSuccessfulSyncRunId = lastSuccessfulSyncRunId;
    }

    public long getAbsenceCount() { return absenceCount; }
    public void setAbsenceCount(long absenceCount) { this.absenceCount = absenceCount; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
