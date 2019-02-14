import React from 'react';
import PropTypes from 'prop-types';
import {Link} from 'react-router-dom';

class BreadcrumbComponent extends React.Component {
    static propTypes = {
        path: PropTypes.string.isRequired
    };

    /**
     * returns a string composed of the parts of the array up to the provided index
     * @param array array to act on
     * @param idx number of parts to join
     */
    recombineParts(array,idx) {
        return "/" + array.slice(0, idx).join("/");
    }

    render(){
        const pathParts = this.props.path.split(/\/+/).filter(elem=>elem!=="");
        const pathPartsLength = pathParts.length;

        return <span className="breadcrumb">
            {
                pathParts.map((part,idx)=>{
                    return <span key={idx}><Link className="breadcrumb" to={this.recombineParts(pathParts,idx+1)}>{part}</Link>{ idx<pathPartsLength ? ">" : ""  }</span>
                })
            }
        </span>
    }
}

export default BreadcrumbComponent;