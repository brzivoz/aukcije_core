package rs.sud.eaukcija.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import rs.sud.eaukcija.model.Auction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class AuctionSpecifications {

    public static Specification<Auction> withFilters(
            String municipality,
            String placeName,
            String category,
            String status,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Boolean firstSale,
            String search
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (municipality != null && !municipality.isBlank()) {
                predicates.add(cb.equal(root.get("municipality"), municipality));
            }
            if (placeName != null && !placeName.isBlank()) {
                predicates.add(cb.equal(root.get("placeName"), placeName));
            }
            if (category != null && !category.isBlank()) {
                predicates.add(cb.equal(root.get("categoryName"), category));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (minPrice != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startingPrice"), minPrice));
            }
            if (maxPrice != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("startingPrice"), maxPrice));
            }
            if (firstSale != null) {
                predicates.add(cb.equal(root.get("firstSale"), firstSale));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("shortDescription")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern),
                        cb.like(cb.lower(root.get("auctionNumber")), pattern)
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
