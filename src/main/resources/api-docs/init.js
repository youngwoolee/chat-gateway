window.onload = () => {
  window.ui = SwaggerUIBundle({
    urls: [
      {url: './external-api.yaml', name: 'FE · 세션 / 채팅 / 클릭'},
      {url: './python-internal-api.yaml', name: 'B/E · 양방향 연동'}
    ],
    dom_id: '#swagger-ui',
    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
    layout: 'StandaloneLayout',
    deepLinking: true,
    filter: true,
    withCredentials: true,
    persistAuthorization: false,
    validatorUrl: null,
    queryConfigEnabled: false
  });
};
