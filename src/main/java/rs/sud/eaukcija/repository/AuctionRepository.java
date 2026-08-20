package rs.sud.eaukcija.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import rs.sud.eaukcija.model.Auction;

import java.util.List;

public interface AuctionRepository extends JpaRepository<Auction, Long>, JpaSpecificationExecutor<Auction> {

    @Query("SELECT DISTINCT a.municipality FROM Auction a WHERE a.municipality IS NOT NULL ORDER BY a.municipality")
    List<String> findDistinctMunicipalities();

    @Query("SELECT DISTINCT a.placeName FROM Auction a WHERE a.placeName IS NOT NULL ORDER BY a.placeName")
    List<String> findDistinctPlaceNames();

    @Query("SELECT DISTINCT a.categoryName FROM Auction a WHERE a.categoryName IS NOT NULL ORDER BY a.categoryName")
    List<String> findDistinctCategories();

    @Query("SELECT DISTINCT a.status FROM Auction a WHERE a.status IS NOT NULL ORDER BY a.status")
    List<String> findDistinctStatuses();

    long countByDetailsFetched(boolean fetched);
}
