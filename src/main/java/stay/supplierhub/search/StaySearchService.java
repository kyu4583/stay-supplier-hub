package stay.supplierhub.search;

import java.util.List;
import org.springframework.stereotype.Service;
import stay.supplierhub.api.StaySearchRequest;
import stay.supplierhub.api.StaySearchResponse;

public interface StaySearchService {

    StaySearchResponse search(StaySearchRequest request);
}

@Service
class EmptyStaySearchService implements StaySearchService {

    @Override
    public StaySearchResponse search(StaySearchRequest request) {
        return new StaySearchResponse(List.of(), List.of());
    }
}
