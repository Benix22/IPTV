FROM node:24-slim
WORKDIR /app
COPY package.json server.js index.html ./
RUN mkdir /data && chown node:node /data
ENV HOST=0.0.0.0 PORT=3000 DATA_DIR=/data
VOLUME /data
EXPOSE 3000
USER node
CMD ["node", "server.js"]
