package stay.supplierhub.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stay.supplierhub.search.StaySearchService;

@RestController
@RequestMapping("/api/v1/stays")
public class StaySearchController {

    private final StaySearchService staySearchService;

    public StaySearchController(StaySearchService staySearchService) {
        this.staySearchService = staySearchService;
    }

    @GetMapping("/search")
    public StaySearchResponse search(@Valid StaySearchRequest request) {
        return staySearchService.search(request);
    }
}
