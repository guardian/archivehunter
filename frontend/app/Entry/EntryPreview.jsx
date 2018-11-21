import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import EntryThumbnail from './EntryThumbnail.jsx';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import ReactTooltip from 'react-tooltip';

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

    updatePreview(){
        this.setState({lastError: null, previewData:null, loading: true}, ()=>axios.get("/api/proxy/" + this.props.entryId)
            .then(result=>{
                this.setState({previewData: result.data.entry, loading:false, lastError: null});
            }).catch(err=>{
                this.setState({previewData: null, loading: false, lastError: err});
            })
        );
    }

    componentWillMount(){
        this.updatePreview();
    }

    componentDidUpdate(oldProps, oldState){
        if(oldProps.entryId!==this.props.entryId) this.updatePreview();
    }

    controlBody(){
        if(!this.state.previewData) return <EntryThumbnail mimeType={this.props.mimeType} fileExtension={this.props.fileExtension} entryId={this.props.entryId}/>;
    }

    render(){
        //if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;

        const tooltip = this.state.previewData ? "" : "No preview available";

        return <span data-tip={tooltip}>{this.controlBody()}<ReactTooltip/></span>
    }
}

export default EntryPreview;