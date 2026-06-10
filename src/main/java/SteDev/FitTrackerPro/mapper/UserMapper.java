package SteDev.FitTrackerPro.mapper;

import SteDev.FitTrackerPro.domain.dto.response.UserResponse;
import SteDev.FitTrackerPro.domain.entity.User;
import org.mapstruct.Mapper;
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserResponse toUserResponse(User user);
}
