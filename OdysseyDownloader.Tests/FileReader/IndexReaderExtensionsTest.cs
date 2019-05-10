using FluentAssertions;
using Moq;
using Odyssey_Downloader;
using Odyssey_Downloader.Model;
using System;
using System.Collections.Generic;
using System.Text;
using Xunit;

namespace OdysseyDownloader.Tests.FileReader
{
    public class IndexReaderExtensionsTest
    {
        private readonly Mock<IIndexReader> _indexReaderMock;

        public IndexReaderExtensionsTest()
        {
            _indexReaderMock = new Mock<IIndexReader>();
        }

        [Fact]
        public void HasTitle_should_return_true_if_it_exists()
        {
            var expected = Guid.NewGuid().ToString();
            _indexReaderMock.Setup(x => x.ReadIndex()).Returns(new[] { new AudioFile { Title = expected } });
            _indexReaderMock.Object.HasTitle(expected).Should().BeTrue();
        }

        [Fact]
        public void HasTitle_should_return_false_if_not_exists()
        {
            var expected = Guid.NewGuid().ToString();
            _indexReaderMock.Setup(x => x.ReadIndex()).Returns(new[] { new AudioFile { Title = Guid.NewGuid().ToString()} });
            _indexReaderMock.Object.HasTitle(expected).Should().BeFalse();
        }
    }
}
