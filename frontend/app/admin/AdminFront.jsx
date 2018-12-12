import React from 'react';
import BreadcrumbComponent from '../common/BreadcrumbComponent.jsx';
import {Link} from 'react-router-dom';

class AdminFront extends React.Component {
    render(){
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <ul>
                <li><Link to="/admin/scanTargets">Scan Targets</Link></li>
                <li><Link to="/admin/jobs">Jobs</Link></li>
                <li><Link to="/admin/users">Users</Link></li>
            </ul>
        </div>
    }
}

export default AdminFront;