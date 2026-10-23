package ru.itmo.highload_ml.project.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload_ml.project.api.dto.CreateTagRequest;
import ru.itmo.highload_ml.project.api.dto.TagResponse;
import ru.itmo.highload_ml.project.exception.TagNameAlreadyTakenException;
import ru.itmo.highload_ml.project.exception.TagNotFoundException;
import ru.itmo.highload_ml.project.model.Tag;
import ru.itmo.highload_ml.project.repository.TagRepository;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    @Transactional
    public TagResponse create(CreateTagRequest request) {
        if (tagRepository.existsByName(request.name())) {
            throw new TagNameAlreadyTakenException(request.name());
        }
        return toResponse(tagRepository.saveAndFlush(new Tag(request.name())));
    }

    public TagResponse getById(UUID tagId) {
        return toResponse(tagRepository.findById(tagId).orElseThrow(() -> new TagNotFoundException(tagId)));
    }

    public Page<TagResponse> findAll(Pageable pageable) {
        return tagRepository.findAll(pageable).map(TagService::toResponse);
    }

    private static TagResponse toResponse(Tag tag) {
        return new TagResponse(tag.getId(), tag.getName());
    }
}
