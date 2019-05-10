using Moq;
using Odyssey_Downloader;
using Odyssey_Downloader.Model;
using SimpleFixture;
using System;
using System.Collections.Generic;
using System.Text;
using Xunit;

namespace OdysseyDownloader.Tests
{
    public class ProcessFileTest
    {
        Mock<IFileInfo> _fileInfoMock;
        private readonly Config _config;
        private readonly Mock<IIndexReader> _indexReaderMock;
        private readonly Mock<IDownloader> _downloaderMock;
        private ProcessFile it;

        public ProcessFileTest()
        {
            _fileInfoMock = new Mock<IFileInfo>();
            _fileInfoMock.SetupGet(x => x.Title).Returns(Guid.NewGuid().ToString());
            _fileInfoMock.SetupGet(x => x.FileUrl).Returns(Guid.NewGuid().ToString());
            _fileInfoMock.SetupGet(x => x.FileName).Returns(Guid.NewGuid().ToString());

            _config = new Config();
            _indexReaderMock = new Mock<IIndexReader>();
            _downloaderMock = new Mock<IDownloader>();
            it = new ProcessFile(_config, _indexReaderMock.Object, _downloaderMock.Object);
        }

        [Fact]
        public void it_should_download_file()
        {
            it.Download(_fileInfoMock.Object);
            _downloaderMock.Verify(x => x.GetFile(_fileInfoMock.Object.FileUrl, _fileInfoMock.Object.FileName));
        }

        [Fact]
        public void it_should_write_file_to_index()
        {
            it.Download(_fileInfoMock.Object);
            _indexReaderMock.Verify(x => x.WriteToIndex(It.Is<AudioFile>(a => a.Title == _fileInfoMock.Object.Title)), Times.Once);
        }
    }
}
