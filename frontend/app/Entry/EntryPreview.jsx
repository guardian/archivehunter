import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import EntryThumbnail from './EntryThumbnail.jsx';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';

class EntryPreview extends React.Component {
    static propTypes = {
        entryId: PropTypes.string.isRequired,
        mimeType: PropTypes.string.isRequired,
        fileExtension: PropTypes.string.isRequired,
        hasProxy: PropTypes.bool.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            previewData: null,
            loading: false,
            lastError: null
        }
    }

    componentWillMount(){
        if(this.props.hasProxy) this.setState({lastError: null, loading: true}, ()=>axios.get("/api/proxy/" + this.props.entryId + "/best")
            .then(result=>{
                this.setState({previewData: result.data.entry, loading:false, lastError: null});
            }).catch(err=>{
                this.setState({previewData: null, loading: false, lastError: err});
            })
        );
    }

    render(){
        if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;
        if(!this.state.previewData) return <EntryThumbnail mimeType={this.props.mimeType} fileExtension={this.props.fileExtension} entryId={this.props.entryId}/>;
    }
}

export default EntryPreview;