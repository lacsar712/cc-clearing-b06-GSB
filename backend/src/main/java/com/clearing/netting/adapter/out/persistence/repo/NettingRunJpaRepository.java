package com.clearing.netting.adapter.out.persistence.repo;

import com.clearing.netting.adapter.out.persistence.entity.NettingRunJpaEntity;
import com.clearing.netting.domain.model.NettingRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NettingRunJpaRepository extends JpaRepository<NettingRunJpaEntity, String> {
    List<NettingRunJpaEntity> findAllByOrderByCreatedAtDesc();

    List<NettingRunJpaEntity> findByStatus(NettingRunStatus status);
}
