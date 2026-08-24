package rs.sud.eaukcija.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

public class EAukcijaApiTypes {

    // --- Request DTOs ---

    public record CategoryRequest(
            @JsonProperty("CategoryId") int categoryId,
            @JsonProperty("ItemCount") int itemCount,
            @JsonProperty("PageCount") int pageCount
    ) {}

    public record DetailRequest(
            @JsonProperty("AuctionId") long auctionId
    ) {}

    // --- Response DTOs ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiResponse<T>(
            @JsonProperty("ResultCode") String resultCode,
            @JsonProperty("ResultMessage") String resultMessage,
            @JsonProperty("Data") T data
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryNode(
            @JsonProperty("title") String title,
            @JsonProperty("value") int value,
            @JsonProperty("key") String key,
            @JsonProperty("children") List<CategoryNode> children,
            @JsonProperty("categoryType") String categoryType
    ) {}

    public record CategoryTree(
            List<CategoryNode> roots,
            String canonicalJson,
            String canonicalSha256
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuctionListData(
            @JsonProperty("Auctions") List<AuctionSummary> auctions,
            @JsonProperty("TotalCount") Integer totalCount
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuctionSummary(
            @JsonProperty("Id") long id,
            @JsonProperty("AuctionNumber") String auctionNumber,
            @JsonProperty("StartDate") String startDate,
            @JsonProperty("EndDate") String endDate,
            @JsonProperty("StartingPrice") BigDecimal startingPrice,
            @JsonProperty("CurrentPrice") BigDecimal currentPrice,
            @JsonProperty("MaxOfferedPrice") BigDecimal maxOfferedPrice,
            @JsonProperty("ShortDescription") String shortDescription,
            @JsonProperty("Status") String status,
            @JsonProperty("IsFirstSale") boolean firstSale,
            @JsonProperty("PropertyType") String propertyType
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuctionDetail(
            @JsonProperty("Id") long id,
            @JsonProperty("AuctionNumber") String auctionNumber,
            @JsonProperty("StartDate") String startDate,
            @JsonProperty("EndDate") String endDate,
            @JsonProperty("PublicationDate") String publicationDate,
            @JsonProperty("StartingPrice") BigDecimal startingPrice,
            @JsonProperty("EstimatedPrice") BigDecimal estimatedPrice,
            @JsonProperty("CurrentPrice") BigDecimal currentPrice,
            @JsonProperty("MaxOfferedPrice") BigDecimal maxOfferedPrice,
            @JsonProperty("BidStep") BigDecimal bidStep,
            @JsonProperty("ShortDescription") String shortDescription,
            @JsonProperty("Description") String description,
            @JsonProperty("Status") String status,
            @JsonProperty("IsFirstSale") boolean firstSale,
            @JsonProperty("PropertyType") String propertyType,
            @JsonProperty("ExecutorName") String executorName,
            @JsonProperty("Category") Category category,
            @JsonProperty("Place") Place place
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(
            @JsonProperty("Id") int id,
            @JsonProperty("Name") String name
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(
            @JsonProperty("Id") int id,
            @JsonProperty("Name") String name,
            @JsonProperty("ZipCode") String zipCode,
            @JsonProperty("Municipality") String municipality,
            @JsonProperty("Cadastral") String cadastral
    ) {}
}
