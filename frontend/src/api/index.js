import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const data = error?.response?.data
    const err = new Error(data?.message || error?.message || '请求失败')
    err.status = error?.response?.status
    err.data = data?.data || null
    return Promise.reject(err)
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 原水监测 ----------------
  listRawWaters: (p) => call(http.get('/raw-waters', params(p))),
  createRawWater: (d) => call(http.post('/raw-waters', d)),
  updateRawWater: (id, d) => call(http.put(`/raw-waters/${id}`, d)),
  reclassifyRawWater: (id, status) =>
    call(http.post(`/raw-waters/${id}/reclassify`, null, params({ status }))),

  // ---------------- 原水超标联动处置 ----------------
  listDisposals: (p) => call(http.get('/raw-water-disposals', params(p))),
  getDisposal: (id) => call(http.get(`/raw-water-disposals/${id}`)),
  createDisposal: (d) => call(http.post('/raw-water-disposals', d)),
  addDisposalEquipment: (id, d) => call(http.post(`/raw-water-disposals/${id}/equipment`, d)),
  confirmDisposalEquipment: (id, linkId, d) =>
    call(http.post(`/raw-water-disposals/${id}/equipment/${linkId}/confirm`, d)),
  resolveDisposalConflict: (id, linkId, d) =>
    call(http.post(`/raw-water-disposals/${id}/equipment/${linkId}/resolve-conflict`, d)),
  removeDisposalEquipment: (id, linkId, d) =>
    call(http.post(`/raw-water-disposals/${id}/equipment/${linkId}/remove`, d)),
  startDisposal: (id, d) => call(http.post(`/raw-water-disposals/${id}/start`, d)),
  releaseDisposal: (id, d) => call(http.post(`/raw-water-disposals/${id}/release`, d)),
  refreshDisposalConflicts: (id, d) =>
    call(http.post(`/raw-water-disposals/${id}/refresh-conflicts`, d)),
  registerDisposalDosing: (id, d) =>
    call(http.post(`/raw-water-disposals/${id}/dosings`, d)),

  // ---------------- 沉淀池 ----------------
  listTanks: (p) => call(http.get('/sedimentation-tanks', params(p))),
  createTank: (d) => call(http.post('/sedimentation-tanks', d)),
  updateTank: (id, d) => call(http.put(`/sedimentation-tanks/${id}`, d)),
  transferTank: (id, target) =>
    call(http.post(`/sedimentation-tanks/${id}/transfer`, null, params({ target }))),

  // ---------------- 滤池 ----------------
  listFilters: (p) => call(http.get('/filters', params(p))),
  createFilter: (d) => call(http.post('/filters', d)),
  updateFilter: (id, d) => call(http.put(`/filters/${id}`, d)),
  backwashFilter: (id) => call(http.post(`/filters/${id}/backwash`)),
  resumeFilter: (id) => call(http.post(`/filters/${id}/resume`)),
  transferFilter: (id, target) =>
    call(http.post(`/filters/${id}/transfer`, null, params({ target }))),

  // ---------------- 加药 ----------------
  listDosings: (p) => call(http.get('/dosings', params(p))),
  createDosing: (d) => call(http.post('/dosings', d)),
  updateDosing: (id, d) => call(http.put(`/dosings/${id}`, d))
}
