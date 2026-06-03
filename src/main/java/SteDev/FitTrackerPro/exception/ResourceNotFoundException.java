package SteDev.FitTrackerPro.exception;

import lombok.Getter;

@Getter
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceName;
//    Using Obj and Not long because IDs across the app won't all be the same. This keeps the exception reusable across every entity.
    private final Object resourceId;

    public ResourceNotFoundException(String resourceName, Object resourceId) {
        super(resourceName + " with ID '" + resourceId + "' was not found");
        this.resourceName = resourceName;
        this.resourceId = resourceId;
    }

}
