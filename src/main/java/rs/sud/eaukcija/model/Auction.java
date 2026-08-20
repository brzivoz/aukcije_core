package rs.sud.eaukcija.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

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
}
