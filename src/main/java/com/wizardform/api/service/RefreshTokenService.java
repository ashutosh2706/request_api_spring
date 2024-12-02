package com.wizardform.api.service;

import com.wizardform.api.exception.ExpiredRefreshTokenException;
import com.wizardform.api.exception.InvalidRefreshTokenException;
import com.wizardform.api.exception.UserNotFoundException;
import com.wizardform.api.model.RefreshToken;
import com.wizardform.api.model.User;
import org.springframework.stereotype.Service;

@Service
public interface RefreshTokenService {
    RefreshToken generateRefreshToken(String username) throws UserNotFoundException;
    User getUserForRefreshToken(String token) throws InvalidRefreshTokenException, ExpiredRefreshTokenException;
}
