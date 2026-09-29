import { ref, reactive } from "vue";
import { storeToRefs } from "pinia";
import { formatStringDate, kbFileTypeVerification } from "../utils/index";
import { MessagePlugin } from "tdesign-vue-next";
import {
  uploadKnowledgeFile,
  listKnowledgeFiles,
  getKnowledgeDetails,
  getKnowledgeDetailsCon,
} from "@/api/knowledge-base/index";
import { knowledgeStore } from "@/stores/knowledge";
import { useUIStore } from "@/stores/ui";
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';

export default function (knowledgeBaseId?: string) {
  const usemenuStore = knowledgeStore();
  const route = useRoute();
  const { t } = useI18n();
  const { cardList, total } = storeToRefs(usemenuStore);
  let moreIndex = ref(-1);
  const details = reactive({
    title: "",
    time: "",
    md: [] as any[],
    id: "",
    total: 0,
    type: "",
    source: "",
    channel: "",
    fileType: "",
    description: "",
    summaryStatus: "",
    parseStatus: "",
    errorMessage: "",
	customMetadata: {} as Record<string, unknown>,
    chunkLoading: false,
    chunkLoadError: "",
    tags: [] as Array<{ id: string; name: string; color?: string }>,
  });
  let knowledgeListGeneration = 0;
  let chunkRequestGeneration = 0;
  let activeKnowledgeId = '';
  const getKnowled = (
    query: {
      page: number;
      pageSize: number;
      tag_ids?: string;
      keyword?: string;
      fileType?: string;
      parseStatus?: string;
      source?: string;
      start_time?: string;
      end_time?: string;
      folderPath?: string;
      folderRecursive?: boolean;
    } = { page: 1, pageSize: 35 },
    kbId?: string,
  ): Promise<void> => {
    const targetKbId = kbId || knowledgeBaseId;
    if (!targetKbId) return Promise.resolve();
    const requestGeneration = query.page === 1 ? ++knowledgeListGeneration : knowledgeListGeneration;

    return listKnowledgeFiles(targetKbId, query)
      .then((result: any) => {
        if (requestGeneration !== knowledgeListGeneration) return;

        const currentRouteKbId = (route.params as any)?.kbId as string | undefined;
        if (currentRouteKbId && currentRouteKbId !== targetKbId) return;

        const { items: data, total: totalResult } = result;
    const cardList_ = data.map((item: any) => {
      const rawName = item.fileName || item.title || item.source || t('knowledgeBase.untitledDocument')
      const dotIndex = rawName.lastIndexOf('.')
      const displayName = dotIndex > 0 ? rawName.substring(0, dotIndex) : rawName
      const fileTypeSource = item.fileType || (item.type === 'manual' ? 'MANUAL' : '')
      return {
        ...item,
        original_file_name: item.fileName,
        display_name: displayName,
        fileName: displayName,
        folderPath: item.folderPath || '',
        updated_at: formatStringDate(new Date(item.updated_at)),
        isMore: false,
        fileType: fileTypeSource ? String(fileTypeSource).toLocaleUpperCase() : '',
      }
    });
        
        if (query.page === 1) {
          cardList.value = cardList_;
        } else {
          cardList.value.push(...cardList_);
        }
        total.value = totalResult;
      })
      .catch(() => {});
  };
  const openMore = (index: number) => {
    moreIndex.value = index;
  };
  const onVisibleChange = (visible: boolean) => {
    if (!visible) {
      moreIndex.value = -1;
    }
  };
  const requestMethod = (file: any, uploadInput: any) => {
    if (!(file instanceof File) || !uploadInput) {
      MessagePlugin.error(t('error.invalidFileType'));
      return;
    }
    
    if (kbFileTypeVerification(file)) {
      return;
    }
    
    // 获取当前知识库ID
    let currentKbId: string | undefined = (route.params as any)?.kbId as string;
    if (!currentKbId && typeof window !== 'undefined') {
      const match = window.location.pathname.match(/knowledge-bases\/([^/]+)/);
      if (match?.[1]) currentKbId = match[1];
    }
    if (!currentKbId) {
      currentKbId = knowledgeBaseId;
    }
    if (!currentKbId) {
      MessagePlugin.error(t('error.missingKbId'));
      return;
    }
    
    // 获取当前选中的标签 ID
    const uiStore = useUIStore();
    const tagIdsToUpload = uiStore.selectedTagIds.length > 0 ? [...uiStore.selectedTagIds] : undefined;

    // 新契约：201 直接返回新文档（无 {success,data} 信封）；失败走 rejection
    // （内容重复是 409 + error.code=2400，见错误码表）。
    uploadKnowledgeFile(currentKbId, { file, tagIds: tagIdsToUpload })
      .then(() => {
        MessagePlugin.info(t('knowledgeBase.uploadSuccess'));
        getKnowled({ page: 1, pageSize: 35 }, currentKbId);
        uploadInput.value.value = "";
      })
      .catch((err: any) => {
        const errorMessage = err.error?.message || err.message || t('knowledgeBase.uploadFailed');
        MessagePlugin.error(err.error?.code === 2400 ? t('knowledgeBase.fileExists') : errorMessage);
        uploadInput.value.value = "";
      });
  };
  const getCardDetails = (item: any) => {
    activeKnowledgeId = item.id;
    chunkRequestGeneration++;
    Object.assign(details, {
      title: "",
      time: "",
      md: [],
      id: "",
      type: "",
      source: "",
      channel: "",
      fileType: "",
      description: "",
      summaryStatus: "",
      parseStatus: "",
      errorMessage: "",
	  customMetadata: {},
      chunkLoadError: "",
      tags: item?.tags ? [...item.tags] : [],
    });
    getKnowledgeDetails(item.id)
      .then((result: any) => {
        if (result) {
          const data = result;
          Object.assign(details, {
            title: data.fileName || data.title || data.source || t('knowledgeBase.untitledDocument'),
            time: formatStringDate(new Date(data.updatedAt)),
            id: data.id,
            type: data.type || 'file',
            source: data.source || '',
            channel: data.channel || '',
            fileType: data.fileType || '',
            description: data.description || '',
            summaryStatus: data.summaryStatus || '',
            parseStatus: data.parseStatus || '',
            errorMessage: data.errorMessage || '',
			customMetadata: data.customMetadata || {},
            tags: data.tags?.length ? data.tags : (item?.tags || []),
          });
        }
      })
      .catch(() => {});
    getfDetails(item.id, 1);
  };
  
  const getfDetails = (id: string, page: number) => {
    const requestGeneration = ++chunkRequestGeneration;
    details.chunkLoading = true;
    details.chunkLoadError = "";
    getKnowledgeDetailsCon(id, page)
      .then((result: any) => {
        if (requestGeneration !== chunkRequestGeneration || activeKnowledgeId !== id) return;
        if (result) {
          const { items: data, total: totalResult } = result;
          details.md = data;
          details.total = totalResult;
        } else {
          details.chunkLoadError = result?.message || result?.error?.message || t('knowledgeBase.chunkLoadFailed');
        }
      })
      .catch((err: any) => {
        if (requestGeneration !== chunkRequestGeneration || activeKnowledgeId !== id) return;
        details.chunkLoadError = err?.message || t('knowledgeBase.chunkLoadFailed');
        console.error("[ChunkLoad] failed", {
          knowledgeId: id,
          page,
          error: err,
        });
      })
      .finally(() => {
        if (requestGeneration === chunkRequestGeneration) {
          details.chunkLoading = false;
        }
      });
  };
  return {
    cardList,
    moreIndex,
    getKnowled,
    details,
    openMore,
    onVisibleChange,
    requestMethod,
    getCardDetails,
    total,
    getfDetails,
  };
}
