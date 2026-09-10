package rs.sud.eaukcija.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class AuctionPresentationTest {
    @Test void unicodeBoundsDoNotSplitSupplementaryCharactersAndUnsafeControlsDoNotSpoofLabels() {
        assertThat(AuctionPresentation.text(" 🧭".repeat(300), 256).codePointCount(0,
                AuctionPresentation.text(" 🧭".repeat(300), 256).length())).isLessThanOrEqualTo(256);
        assertThat(AuctionPresentation.text("Н\u0000\u202e51\n   Београд", 256)).isEqualTo("Н51 Београд");
        assertThat(AuctionPresentation.text("\u202e\u0000", 256)).isNull();
        assertThat(AuctionPresentation.category(null)).isEqualTo("Категорија није наведена");
    }
    @Test void completeDescriptionPreservesParagraphsSpacingAndHostileLookingMarkupAsText() {
        String description = "Њива  Čačak\n\n<img src=x>\t🧭";
        assertThat(AuctionPresentation.description(description, 4000)).isEqualTo(description);
        assertThat(AuctionPresentation.description("О".repeat(4000), 4000)).hasSize(4000);
        assertThat(AuctionPresentation.description("О".repeat(5000), 4000)).hasSize(4000);
    }
    @Test void statusesDescribeSourceWorkflowOnlyAndUnknownValuesAreExplicit() {
        assertThat(AuctionPresentation.statusLabel("Verified")).isEqualTo("Проверено на извору");
        assertThat(AuctionPresentation.statusLabel("Closed")).isEqualTo("Затворено на извору");
        assertThat(AuctionPresentation.statusLabel("Strange<svg>\u202e")).isEqualTo("Непознат изворни статус: Strange<svg>");
        assertThat(AuctionPresentation.statusLabel(null)).isEqualTo("Статус није познат");
    }
}
